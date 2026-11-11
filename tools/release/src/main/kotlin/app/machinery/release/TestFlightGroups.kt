package app.machinery.release

import app.machinery.release.AppStoreDocuments.changes
import app.machinery.release.AppStoreDocuments.create
import app.machinery.release.AppStoreDocuments.link
import app.machinery.release.AppStoreDocuments.links
import app.machinery.release.AppStoreDocuments.update
import app.machinery.release.AppStoreDocuments.value
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * An external TestFlight group of the listing, people outside the team who test the app once Beta App Review lets a
 * build through. `testflight.externalGroups` lists the groups by ids of the listing's choosing, and each one's
 * settings are `testflight.externalGroup.<id>.<setting>`:
 * - `name`: the group's name in App Store Connect (the id when missing);
 * - `builds`: `releases` (the default) to get every release's build, or `manual` to get only the builds chosen in App
 *   Store Connect;
 * - `publicLink`: `true` for a link anyone can join the group with, up to `publicLinkLimit` testers (Apple's 10,000
 *   when missing);
 * - `feedback`: `false` to turn off the screenshots and comments testers send from TestFlight.
 */
data class ExternalGroup(
    val name: String,
    val everyRelease: Boolean = true,
    val publicLink: Boolean = false,
    val publicLinkLimit: Int? = null,
    val feedback: Boolean = true,
) {
    companion object {
        /** The most testers a public link takes, and an app's external testers in all. */
        const val MAX_TESTERS = 10_000

        private const val GROUPS = "testflight.externalGroups"
        private const val PREFIX = "testflight.externalGroup."
        private val BUILDS = mapOf("releases" to true, "manual" to false)

        /** The external groups of [listing], which [problems] found nothing wrong with. */
        fun of(listing: AppStoreListing): List<ExternalGroup> =
            listing.list(GROUPS).map { id ->
                val setting = { key: String -> listing.setting("$PREFIX$id.$key") }
                ExternalGroup(
                    name = setting("name") ?: id,
                    everyRelease = setting("builds")?.let(BUILDS::getValue) ?: true,
                    publicLink = setting("publicLink")?.toBooleanStrict() ?: false,
                    publicLinkLimit = setting("publicLinkLimit")?.toInt(),
                    feedback = setting("feedback")?.toBooleanStrict() ?: true,
                )
            }

        /** What is wrong with the external groups of [listing]'s settings. */
        fun problems(listing: AppStoreListing): List<String> {
            val ids = listing.list(GROUPS)
            val names = ids.map { listing.setting("$PREFIX$it.name") ?: it }
            val twice =
                names
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            return ids.flatMap { id ->
                val key = { name: String -> "$PREFIX$id.$name" }
                val setting = { name: String -> listing.setting(key(name)) }
                listOfNotNull(
                    setting("builds")?.takeIf { it !in BUILDS }?.let { "${key("builds")}: releases or manual" },
                    setting("publicLink")?.takeIf { it.toBooleanStrictOrNull() == null }?.let {
                        "${key("publicLink")}: true or false"
                    },
                    setting("feedback")?.takeIf { it.toBooleanStrictOrNull() == null }?.let {
                        "${key("feedback")}: true or false"
                    },
                    setting("publicLinkLimit")?.takeIf { it.toIntOrNull() !in 1..MAX_TESTERS }?.let {
                        "${key("publicLinkLimit")}: a number from 1 to $MAX_TESTERS"
                    },
                )
            } + twice.map { "$GROUPS: two groups are named \"$it\"" }
        }
    }
}

/**
 * TestFlight for people outside the team: the listing's [ExternalGroup]s, each with its public link, the release's
 * build in those that get every release, its "What to Test" (the listing's `what_to_test.txt`, then the release's
 * notes), and the build sent to Beta App Review with the details that review needs (`testflight.review.<field>`,
 * else App Review's `review.<field>` and notes). Only the owner can give the contact's name and phone.
 *
 * The notes are the release's by Google Play's language codes, as the release writes them for Google Play: a run
 * without them (one started by hand) gives a build only the What to Test it lacks, and never replaces one.
 */
class ExternalTesting(
    private val api: AppStoreApi,
    private val listing: AppStoreListing,
    private val localizations: Localizations,
    private val builds: BuildChoice,
    private val notes: Map<String, String>,
    private val report: AppStoreReport,
) {
    /** Brings the external groups up to date and sends them the build. */
    fun sync(app: AppRecord) {
        val groups = ExternalGroup.of(listing)
        if (groups.isEmpty()) return
        var reviewable = false
        report.step("Beta App Review information") { reviewable = reviewDetails(app) }
        val existing =
            api
                .getAll("/v1/apps/${app.id}/betaGroups?limit=200")
                .filter { it.attributes["isInternalGroup"]?.jsonPrimitive?.booleanOrNull == false }
        val ids = mutableMapOf<ExternalGroup, String>()
        groups.forEach { group ->
            report.step("TestFlight group \"${group.name}\"") { ids[group] = sync(app, group, existing) }
        }
        val receiving = groups.filter { it.everyRelease }.mapNotNull(ids::get)
        if (receiving.isEmpty()) return
        report.step("TestFlight build for external testers") { build(app, receiving, reviewable) }
    }

    /** Fills in Beta App Review's details, and whether they let a build be sent to it. */
    private fun reviewDetails(app: AppRecord): Boolean {
        val settings = listing.group("review.") + listing.group("testflight.review.")
        val wanted: Map<String, JsonElement> =
            listOfNotNull(listing.reviewNotes?.let { "notes" to JsonPrimitive(it) }).toMap() +
                settings.mapValues { (_, it) -> value(it) }
        val detail = api.get("/v1/apps/${app.id}/betaAppReviewDetail")?.resource
        if (detail == null) {
            report.owner("Beta App Review information (TestFlight → Test Information): App Store Connect has none yet")
            return false
        }
        val changed = changes(detail.attributes, wanted)
        if (changed.isNotEmpty()) {
            api.patch("/v1/betaAppReviewDetails/${detail.id}", update(DETAILS, detail.id, changed))
            report.done("Beta App Review information: ${changed.keys.joinToString()}")
        }
        val attributes = JsonObject(detail.attributes + wanted)
        val missing = CONTACT.filter { attributes.string(it.key).isNullOrBlank() }.values
        if (missing.isNotEmpty()) {
            report.owner(
                "Beta App Review contact (TestFlight → Test Information): ${missing.joinToString()}; until then, " +
                    "builds wait to be sent to Beta App Review",
            )
        }
        return missing.isEmpty()
    }

    /** Makes [group] as the listing has it, among the app's [existing] external groups, and returns its id. */
    private fun sync(
        app: AppRecord,
        group: ExternalGroup,
        existing: List<JsonObject>,
    ): String {
        val wanted = mapOf("feedbackEnabled" to JsonPrimitive(group.feedback))
        val found = existing.firstOrNull { it.attributes.string("name") == group.name }
        val current =
            found ?: checkNotNull(
                api
                    .post(
                        "/v1/betaGroups",
                        create(
                            GROUPS,
                            wanted + (
                                "name" to
                                    JsonPrimitive(
                                        group.name,
                                    )
                            ) + ("isInternalGroup" to JsonPrimitive(false)),
                            mapOf("app" to link("apps", app.id)),
                        ),
                    ).resource,
            ).also { report.done("TestFlight: the external group \"${group.name}\"") }
        val changed =
            changes(current.attributes, if (found == null) emptyMap() else wanted) +
                changes(current.attributes, linkAttributes(group))
        if (changed.isNotEmpty()) {
            // Apple may turn a public link on only once a build of the group has passed Beta App Review
            val refused =
                runCatching { api.patch("/v1/betaGroups/${current.id}", update(GROUPS, current.id, changed)) }
                    .exceptionOrNull()
            if (refused == null) {
                report.done("TestFlight group \"${group.name}\": ${changed.keys.joinToString()}")
            } else {
                report.owner(
                    "TestFlight group \"${group.name}\": App Store Connect refused ${changed.keys.joinToString()} " +
                        "(${refused.message?.lineSequence()?.firstOrNull()}); the next run tries again, or set it " +
                        "in TestFlight",
                )
            }
        }
        val link =
            api
                .get("/v1/betaGroups/${current.id}")
                ?.resource
                ?.attributes
                ?.string("publicLink")
        report.fact(
            "TestFlight group \"${group.name}\": " +
                (link?.let { "public link $it" } ?: "no public link") +
                (if (group.everyRelease) ", every release's build" else ", builds chosen in App Store Connect"),
        )
        return current.id
    }

    private fun linkAttributes(group: ExternalGroup): Map<String, JsonElement> =
        if (!group.publicLink) {
            mapOf("publicLinkEnabled" to JsonPrimitive(false))
        } else {
            mapOf(
                "publicLinkEnabled" to JsonPrimitive(true),
                "publicLinkLimitEnabled" to JsonPrimitive(group.publicLinkLimit != null),
            ) + listOfNotNull(group.publicLinkLimit?.let { "publicLinkLimit" to JsonPrimitive(it) })
        }

    /** Puts the build in the [groups] that lack it, with its What to Test, and sends it to Beta App Review. */
    private fun build(
        app: AppRecord,
        groups: List<String>,
        reviewable: Boolean,
    ) {
        val (build, version) =
            builds.pick(api, app) ?: run {
                report.fact("No build is processed yet for external testers")
                return
            }
        val name = "$version (${build.attributes.string("version")})"
        groups.forEach { group ->
            val has = api.getAll("/v1/betaGroups/$group/relationships/builds?limit=200").any { it.id == build.id }
            if (!has) {
                api.post("/v1/betaGroups/$group/relationships/builds", links("builds", listOf(build.id)))
                report.done("TestFlight: build $name for external testers")
            }
        }
        whatToTest(build)
        val state =
            api
                .get("/v1/builds/${build.id}/buildBetaDetail")
                ?.resource
                ?.attributes
                ?.string("externalBuildState")
        when {
            state == READY && reviewable -> {
                api.post(
                    "/v1/betaAppReviewSubmissions",
                    create("betaAppReviewSubmissions", emptyMap(), mapOf("build" to link("builds", build.id))),
                )
                report.done("Build $name sent to Beta App Review")
            }

            state == "BETA_REJECTED" -> {
                report.owner("Beta App Review turned build $name down: TestFlight in App Store Connect tells why")
            }

            else -> {
                report.fact("Build $name for external testers: ${state ?: "unknown"}")
            }
        }
    }

    /**
     * The build's What to Test (the API's `whatsNew`) in TestFlight's languages, those with a `beta_description.txt`:
     * the listing's text and the release's notes, in characters App Store Connect takes there.
     */
    private fun whatToTest(build: JsonObject) {
        val texts =
            listing.texts
                .filterValues { ListingText.BETA_DESCRIPTION in it }
                .mapValues { (locale, texts) ->
                    val note = PlayLanguages.ofAppStore(locale)?.let(notes::get)?.let(::testFlightText)
                    listOfNotNull(texts[ListingText.WHAT_TO_TEST], note?.takeIf { it.isNotEmpty() })
                        .let { parts ->
                            parts.joinToString("\n\n").takeIf { it.length <= WHAT_TO_TEST_LIMIT } ?: parts.first()
                        }
                }.filterValues { it.isNotEmpty() }
        if (texts.isEmpty()) return
        val path = "/v1/builds/${build.id}/betaBuildLocalizations?limit=200"
        val kept =
            if (notes.isEmpty()) api.getAll(path).mapNotNull { it.attributes.string("locale") }.toSet() else emptySet()
        val wanted =
            texts
                .filterKeys { it !in kept }
                .mapValues { (_, text) -> mapOf<String, JsonElement>("whatsNew" to JsonPrimitive(text)) }
        if (wanted.isEmpty()) return
        localizations.sync("What to Test", path, "betaBuildLocalizations", "build" to link("builds", build.id), wanted)
    }

    private companion object {
        const val GROUPS = "betaGroups"
        const val DETAILS = "betaAppReviewDetails"
        const val READY = "READY_FOR_BETA_SUBMISSION"
        const val WHAT_TO_TEST_LIMIT = 4000

        /** The last character of the Basic Multilingual Plane: App Store Connect refuses any after it (emoji...). */
        const val LAST_BASIC = 0xFFFF

        /** Characters What to Test refuses: variation selectors, and arrows to dingbats and box drawing. */
        val REFUSED = listOf(0xFE00..0xFE0F, 0x2190..0x2BFF)

        /** [notes] as What to Test takes them: dashes for the release notes' bullets, without what it refuses. */
        fun testFlightText(notes: String): String =
            notes
                .replace('•', '-')
                .codePoints()
                .toArray()
                .filter { point -> point <= LAST_BASIC && REFUSED.none { point in it } }
                .joinToString("") { String(Character.toChars(it)) }
                .lines()
                .joinToString("\n") { it.trimEnd() }
                .trim()

        /** The contact Beta App Review needs, by attribute: the owner's own, so never in a repository. */
        val CONTACT =
            mapOf(
                "contactFirstName" to "first name",
                "contactLastName" to "last name",
                "contactPhone" to "phone number",
                "contactEmail" to "email",
            )
    }
}
