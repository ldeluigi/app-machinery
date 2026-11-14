package app.machinery.release

import app.machinery.release.AppStoreDocuments.changes
import app.machinery.release.AppStoreDocuments.create
import app.machinery.release.AppStoreDocuments.link
import app.machinery.release.AppStoreDocuments.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Brings a game's achievements in Game Center in line with its [achievements]: the app's Game Center record if it
 * has none, then each achievement (by its id, App Store Connect's vendor identifier), its texts in the App Store's
 * languages and, in each, its picture from [pictures] (`<id>.png`). What exists and matches stays; nothing is deleted.
 * App Store Connect takes some 3,600 requests an hour: a first run of many achievements in many languages can reach
 * that, and then stops, for the next run to go on. The achievements go live with a version of the app, which the
 * owner sends to App Review.
 */
class GameCenterSync(
    private val api: AppStoreApi,
    private val achievements: Achievements,
    private val pictures: File,
    private val report: AppStoreReport,
) {
    private val refused = mutableMapOf<String, String>()
    private var created = 0
    private var texts = 0
    private var images = 0

    /** Runs the sync for the app of [bundleId]. */
    fun run(bundleId: String) {
        val app =
            api
                .getAll("/v1/apps?filter[bundleId]=${URLEncoder.encode(bundleId, "UTF-8")}&limit=200")
                .firstOrNull { it.attributes.string("bundleId") == bundleId }
        if (app == null) {
            report.step("The app's record") {
                error("App Store Connect has no app with the bundle id $bundleId: make its record first")
            }
            return
        }
        try {
            sync(detail(app.id))
        } catch (e: IOException) {
            if (TOO_MANY in e.message.orEmpty()) {
                report.fact("App Store Connect's hourly limit of requests was reached: the next run goes on from here")
            } else {
                report.step("Game Center") { throw e }
            }
        }
        if (created > 0) report.done("Created $created achievements")
        if (texts > 0) report.done("Wrote $texts achievement texts")
        if (images > 0) report.done("Uploaded $images achievement pictures")
        refused.toSortedMap().forEach { (locale, why) ->
            report.fact("App Store Connect refused the achievements' texts in $locale, left out: ${why.lines().last()}")
        }
        if (created > 0) {
            report.owner(
                "Add the new achievements to the version being prepared: App Store Connect → the version → Game " +
                    "Center (they go live with it)",
            )
        }
    }

    /** The app's Game Center record, made when it has none. */
    private fun detail(app: String): String {
        api.get("/v1/apps/$app/gameCenterDetail")?.resource?.let { return it.id }
        val made =
            api.post(
                "/v1/gameCenterDetails",
                create("gameCenterDetails", relationships = mapOf("app" to link("apps", app))),
            )
        report.done("Switched Game Center on for the app")
        return checkNotNull(made.resource?.id) { "App Store Connect made no Game Center record" }
    }

    private fun sync(detail: String) {
        val existing =
            api
                .getAll("/v1/gameCenterDetails/$detail/gameCenterAchievements?limit=200")
                .associateBy { it.attributes.string("vendorIdentifier") }
        val unknown = existing.keys.filterNotNull() - achievements.list.map { it.id }.toSet()
        if (unknown.isNotEmpty()) {
            report.fact("Game Center also has ${unknown.sorted().joinToString()}, which the app doesn't list")
        }
        report.fact("Game Center has ${achievements.list.size} of the game's achievements")
        for ((achievement, localized) in achievements.localized(AppStoreLocales::codes)) {
            val id = achievement(detail, achievement, existing[achievement.id])
            localizations(id, achievement, localized)
        }
    }

    /** The achievement's id, made or brought up to date. */
    private fun achievement(
        detail: String,
        achievement: Achievement,
        current: JsonObject?,
    ): String {
        val attributes: Map<String, JsonElement> =
            mapOf(
                "referenceName" to JsonPrimitive(achievement.id),
                "points" to JsonPrimitive(achievement.points),
                "showBeforeEarned" to JsonPrimitive(!achievement.secret),
                "repeatable" to JsonPrimitive(false),
            )
        if (current == null) {
            val made =
                api.post(
                    "/v1/gameCenterAchievements",
                    create(
                        "gameCenterAchievements",
                        attributes + ("vendorIdentifier" to JsonPrimitive(achievement.id)),
                        mapOf("gameCenterDetail" to link("gameCenterDetails", detail)),
                    ),
                )
            created++
            return checkNotNull(made.resource?.id) { "App Store Connect made no achievement ${achievement.id}" }
        }
        val change = changes(current.attributes, attributes)
        if (change.isNotEmpty()) {
            api.patch("/v1/gameCenterAchievements/${current.id}", update("gameCenterAchievements", current.id, change))
            report.done("Changed ${achievement.id}'s ${change.keys.joinToString()}")
        }
        return current.id
    }

    /** The achievement's texts and picture in each language. */
    private fun localizations(
        id: String,
        achievement: Achievement,
        localized: Map<String, AchievementText>,
    ) {
        val document =
            api.get("/v1/gameCenterAchievements/$id/localizations?include=gameCenterAchievementImage&limit=200")
                ?: return
        val current = document.resources.associateBy { it.attributes.string("locale") }
        val images = document.included("gameCenterAchievementImages").associateBy { it.id }
        val picture = pictures.resolve("${achievement.id}.png")
        for ((locale, text) in localized.filterKeys { it !in refused }) {
            val existing = current[locale]
            // A language App Store Connect refuses is left out for the rest of the run, the others go on
            val localization =
                runCatching { localization(id, locale, text, existing) }.getOrElse { error ->
                    if (existing != null || error !is IOException || TOO_MANY in error.message.orEmpty()) throw error
                    refused[locale] = error.message.orEmpty()
                    null
                } ?: continue
            picture(localization, picture, existing?.related("gameCenterAchievementImage")?.let(images::get))
        }
    }

    /** The localization's id, made or brought up to date. */
    private fun localization(
        achievement: String,
        locale: String,
        text: AchievementText,
        current: JsonObject?,
    ): String {
        val attributes: Map<String, JsonElement> =
            mapOf(
                "name" to JsonPrimitive(text.name),
                "beforeEarnedDescription" to JsonPrimitive(text.description),
                "afterEarnedDescription" to JsonPrimitive(text.description),
            )
        if (current == null) {
            val made =
                api.post(
                    "/v1/gameCenterAchievementLocalizations",
                    create(
                        "gameCenterAchievementLocalizations",
                        attributes + ("locale" to JsonPrimitive(locale)),
                        mapOf("gameCenterAchievement" to link("gameCenterAchievements", achievement)),
                    ),
                )
            texts++
            return checkNotNull(made.resource?.id) { "App Store Connect made no text in $locale" }
        }
        val change = changes(current.attributes, attributes)
        if (change.isNotEmpty()) {
            api.patch(
                "/v1/gameCenterAchievementLocalizations/${current.id}",
                update("gameCenterAchievementLocalizations", current.id, change),
            )
            texts++
        }
        return current.id
    }

    /** Uploads [file] as the localization's picture, unless [current] is it already; it replaces another. */
    private fun picture(
        localization: String,
        file: File,
        current: JsonObject?,
    ) {
        if (!file.isFile) return
        val name = pictureName(file)
        if (current != null && current.attributes.string("fileName") == name) return
        if (current != null) api.delete("/v1/gameCenterAchievementImages/${current.id}")
        val bytes = file.readBytes()
        val reserved =
            api.post(
                "/v1/gameCenterAchievementImages",
                create(
                    "gameCenterAchievementImages",
                    mapOf("fileName" to JsonPrimitive(name), "fileSize" to JsonPrimitive(bytes.size)),
                    mapOf(
                        "gameCenterAchievementLocalization" to
                            link("gameCenterAchievementLocalizations", localization),
                    ),
                ),
            )
        val image = checkNotNull(reserved.resource) { "App Store Connect made no picture for ${file.name}" }
        UploadOperation.of(image.attributes["uploadOperations"]).forEach { part ->
            api.upload(part, bytes.copyOfRange(part.offset, part.offset + part.length))
        }
        api.patch(
            "/v1/gameCenterAchievementImages/${image.id}",
            update("gameCenterAchievementImages", image.id, mapOf("uploaded" to JsonPrimitive(true))),
        )
        images++
    }

    companion object {
        /** The sizes Game Center takes for an achievement's picture. */
        val PICTURE_SIZES = setOf("512x512", "1024x1024")

        /**
         * The name a picture goes up with: its file's, with a few hex digits of its MD5, so that a picture drawn again
         * differently goes up again, and one drawn the same doesn't.
         */
        fun pictureName(file: File): String = "${file.nameWithoutExtension}-${ScreenshotSync.md5(file).take(HASH)}.png"

        /** What's wrong with the pictures of [achievements] in [dir], if anything. */
        fun pictureProblems(
            achievements: Achievements,
            dir: File,
        ): List<String> =
            achievements.list.mapNotNull { achievement ->
                val file = dir.resolve("${achievement.id}.png")
                val size = if (file.isFile) ScreenshotSync.pngSize(file) else null
                when {
                    size == null -> "No picture for ${achievement.id}: ${file.path}"
                    size !in PICTURE_SIZES -> "${file.path} is $size, Game Center takes ${PICTURE_SIZES.joinToString()}"
                    else -> null
                }
            }

        private const val TOO_MANY = "answered 429"
        private const val HASH = 8
    }
}
