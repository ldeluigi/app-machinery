package app.machinery.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest

/**
 * The Play Games ids an Android app reads, from its resource [file]: the game's project id ([PROJECT]) and each
 * achievement's (`achievement_<id>`), which Play makes when an achievement is created.
 */
class PlayGamesIds(
    private val file: File,
) {
    private val strings: Map<String, String> = if (file.isFile) StringResources.strings(file) else emptyMap()

    /** The game's project id, or null while the file doesn't have it. */
    val project: String? get() = strings[PROJECT]?.takeIf { it.isNotBlank() }

    /** The Play id of the achievement [id], or null while it has none. */
    fun achievement(id: String): String? = strings[key(id)]?.takeIf { it.isNotBlank() }

    /**
     * Writes [ids] (Play's, by achievement id) into the file, keeping everything else in it, a missing string added
     * before its end. Returns the file's new text, or null when nothing changed.
     */
    fun write(ids: Map<String, String>): String? {
        val old = file.takeIf(File::isFile)?.readText(Charsets.UTF_8) ?: return null
        val new = ids.entries.fold(old) { text, (id, value) -> withId(text, key(id), value) }
        return new.takeIf { it != old }?.also { file.writeText(it, Charsets.UTF_8) }
    }

    /** [text] with the string [name] holding [value]. */
    private fun withId(
        text: String,
        name: String,
        value: String,
    ): String {
        val full = Regex("""(<string name="${Regex.escape(name)}"[^>]*>)[^<]*(</string>)""")
        val empty = Regex("""<string name="${Regex.escape(name)}"([^>]*)/>""")
        val opening = "<string name=\"$name\""
        return when {
            full.containsMatchIn(text) -> full.replace(text) { "${it.groupValues[1]}$value${it.groupValues[2]}" }
            empty.containsMatchIn(text) -> empty.replace(text) { "$opening${it.groupValues[1]}>$value</string>" }
            else -> text.replace("</resources>", "    $opening translatable=\"false\">$value</string>\n</resources>")
        }
    }

    companion object {
        /** The string of the game's project id. */
        const val PROJECT = "game_services_project_id"

        /** The string of an achievement's Play id. */
        fun key(id: String): String = "achievement_$id"
    }
}

/** Google Play Games' configuration of a game's achievements, as the achievements tool uses it. */
interface PlayGamesApi {
    /** Every achievement of the game [project], drafts included. */
    fun achievements(project: String): List<JsonObject>

    /** Creates [achievement] in the game [project], and returns it with its id. */
    fun insert(
        project: String,
        achievement: JsonObject,
    ): JsonObject

    /** Replaces the achievement [id]'s configuration with [achievement]. */
    fun update(
        id: String,
        achievement: JsonObject,
    )
}

/**
 * The Google Play Games Services Publishing API (v1configuration), with an OAuth access [token] for its
 * `androidpublisher` scope, the same as Google Play's listing.
 */
class PlayGamesConfiguration(
    private val token: String,
) : PlayGamesApi {
    private val requests = GoogleRequests("Google Play Games", FORBIDDEN_HINT)

    override fun achievements(project: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var page: String? = ""
        while (page != null) {
            val next = if (page.isEmpty()) "" else "&pageToken=${URLEncoder.encode(page, "UTF-8")}"
            val query = "maxResults=$PAGE$next"
            val answer = parse(send(request("applications/$project/achievements?$query").GET()))
            all += answer["items"]?.jsonArray.orEmpty().map { it.jsonObject }
            page = answer.string("nextPageToken")
        }
        return all
    }

    override fun insert(
        project: String,
        achievement: JsonObject,
    ): JsonObject = parse(send(request("applications/$project/achievements").POST(body(achievement))))

    override fun update(
        id: String,
        achievement: JsonObject,
    ) {
        send(request("achievements/$id").PUT(body(achievement)))
    }

    private fun body(json: JsonObject) = HttpRequest.BodyPublishers.ofString(json.toString())

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("$HOST/$path"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")

    private fun send(builder: HttpRequest.Builder): String = requests.send(builder)

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private companion object {
        const val HOST = "https://gamesconfiguration.googleapis.com/games/v1configuration"
        const val PAGE = 200
        const val FORBIDDEN_HINT =
            " (the service account needs the Play Games Services permissions on the app: Play Console → Users and " +
                "permissions)"
    }
}

/**
 * Brings a game's achievements in Google Play Games in line with its [achievements]: creates the ones Play doesn't
 * have, and changes the ones whose texts (in Play's languages), points or visibility changed. An achievement is
 * Play's by its id in [ids], or else by its name in Play's default language, so that a run whose new ids weren't
 * kept doesn't make it twice. The new ids go into [ids]' file. Nothing is deleted, and nothing published: Play
 * Console publishes the games services' changes, and takes the pictures, which the API can't.
 */
class PlayGamesSync(
    private val api: PlayGamesApi,
    private val achievements: Achievements,
    private val ids: PlayGamesIds,
    private val report: AppStoreReport,
) {
    /** Runs the sync; returns the ids file's new text when it changed, for the run's summary. */
    fun run(): String? {
        val project = ids.project
        if (project == null) {
            report.owner(
                "No Play Games project id in ${achievements.setting(Achievements.PLAY_GAMES_IDS)}: create the game's " +
                    "games services project in Play Console and put its id there (${PlayGamesIds.PROJECT})",
            )
            return null
        }
        val existing = api.achievements(project)
        val taken = mutableSetOf<String>()
        val found = mutableMapOf<String, String>()
        var created = 0
        var changed = 0
        for ((achievement, texts) in achievements.localized(PlayLanguages::codes)) {
            val wanted = configuration(achievement, texts)
            val current =
                ids.achievement(achievement.id)?.let { id -> existing.firstOrNull { it.string("id") == id } }
                    ?: existing.firstOrNull { it.string("id") !in taken && sameName(it, wanted) }
            if (current == null) {
                found[achievement.id] = checkNotNull(api.insert(project, wanted).string("id")) { "Play made no id" }
                created++
            } else {
                val id = checkNotNull(current.string("id"))
                found[achievement.id] = id
                if (differs(current, wanted)) {
                    api.update(id, JsonObject(wanted + ("id" to JsonPrimitive(id))))
                    changed++
                }
            }
            taken += found.getValue(achievement.id)
        }
        if (created > 0) report.done("Created $created achievements")
        if (changed > 0) report.done("Changed $changed achievements")
        report.fact("Play Games has ${achievements.list.size} of the game's achievements")
        if (created + changed > 0) {
            report.owner(
                "Publish the games services' changes: Play Console → Play Games Services → Setup and management → " +
                    "Publishing (the API can't)",
            )
        }
        if (created > 0) {
            report.owner(
                "Give the new achievements their icons: Play Console → Play Games Services → Achievements, from " +
                    "StoreArtTest's pictures (the API can't upload them)",
            )
        }
        return ids.write(found)
    }

    private fun differs(
        current: JsonObject,
        wanted: JsonObject,
    ): Boolean {
        val draft = current["draft"] as? JsonObject ?: return true
        val want = wanted.getValue("draft") as JsonObject
        return current.string("initialState") != wanted.string("initialState") ||
            draft.string("pointValue") != want.string("pointValue") ||
            translations(draft, "name") != translations(want, "name") ||
            translations(draft, "description") != translations(want, "description")
    }

    companion object {
        /** An achievement's configuration as Play takes it, with its texts by Play's languages. */
        fun configuration(
            achievement: Achievement,
            texts: Map<String, AchievementText>,
        ): JsonObject =
            buildJsonObject {
                put("achievementType", "STANDARD")
                put("initialState", if (achievement.secret) "HIDDEN" else "REVEALED")
                put(
                    "draft",
                    buildJsonObject {
                        put("name", bundle(texts.mapValues { it.value.name }))
                        put("description", bundle(texts.mapValues { it.value.description }))
                        put("pointValue", achievement.points)
                    },
                )
            }

        private fun bundle(texts: Map<String, String>): JsonObject =
            buildJsonObject {
                put(
                    "translations",
                    buildJsonArray {
                        texts.toSortedMap().forEach { (locale, value) ->
                            add(
                                buildJsonObject {
                                    put("locale", locale)
                                    put("value", value)
                                },
                            )
                        }
                    },
                )
            }

        /** A configuration's texts of [name] (`name`, `description`) by Play's language. */
        fun translations(
            detail: JsonObject,
            name: String,
        ): Map<String, String> =
            ((detail[name] as? JsonObject)?.get("translations") as? JsonArray)
                .orEmpty()
                .map { it.jsonObject }
                .associate { it.string("locale").orEmpty() to it.string("value").orEmpty() }

        private fun defaultName(configuration: JsonObject): String? =
            (configuration["draft"] as? JsonObject)?.let { translations(it, "name")[PlayLanguages.DEFAULT] }

        private fun sameName(
            a: JsonObject,
            b: JsonObject,
        ): Boolean = defaultName(a) != null && defaultName(a) == defaultName(b)
    }
}
