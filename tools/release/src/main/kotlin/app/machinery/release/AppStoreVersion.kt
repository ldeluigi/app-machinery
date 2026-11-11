package app.machinery.release

import app.machinery.release.AppStoreDocuments.changes
import app.machinery.release.AppStoreDocuments.create
import app.machinery.release.AppStoreDocuments.link
import app.machinery.release.AppStoreDocuments.update
import app.machinery.release.AppStoreDocuments.value
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Clock
import java.time.Duration

/**
 * Which build the version being prepared gets: the one of the release [version] once App Store Connect has
 * processed it, waiting up to [wait] for that, or else the newest processed build.
 */
data class BuildChoice(
    val version: String?,
    val wait: Duration = Duration.ZERO,
    val clock: Clock = Clock.systemUTC(),
    val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    /** The build to put in the version being prepared, with its version, or null when none is processed yet. */
    fun pick(
        api: AppStoreApi,
        app: AppRecord,
    ): Pair<JsonObject, String>? {
        val builds = "/v1/builds?filter[app]=${app.id}&filter[preReleaseVersion.platform]=IOS&include=preReleaseVersion"
        return version?.let { release(api, builds, it) } ?: newest(api, builds)
    }

    /** The build of the release [version] once processed, waiting for it as long as [wait] allows. */
    private fun release(
        api: AppStoreApi,
        builds: String,
        version: String,
    ): Pair<JsonObject, String>? {
        val deadline = clock.instant() + wait
        var build: JsonObject? = null
        while (build == null) {
            val found = api.get("$builds&filter[preReleaseVersion.version]=$version&sort=-uploadedDate&limit=1")
            val candidate = found?.resources?.firstOrNull()
            val state = candidate?.attributes?.string("processingState")
            when {
                state == "VALID" -> build = candidate
                state in FAILED || !clock.instant().isBefore(deadline) -> break
                else -> sleep(POLL)
            }
        }
        return build?.let { it to version }
    }

    /** The newest processed build that hasn't expired, with its version. */
    private fun newest(
        api: AppStoreApi,
        builds: String,
    ): Pair<JsonObject, String>? {
        val newest = api.get("$builds&filter[processingState]=VALID&filter[expired]=false&sort=-uploadedDate&limit=1")
        return newest?.resources?.firstOrNull()?.let { build ->
            val marketing =
                newest.included("preReleaseVersions").firstOrNull { it.id == build.related("preReleaseVersion") }
            build to checkNotNull(marketing?.attributes?.string("version")) { "Build ${build.id} has no version" }
        }
    }

    private companion object {
        val POLL: Duration = Duration.ofMinutes(1)
        val FAILED = setOf("FAILED", "INVALID")
    }
}

/**
 * The App Store version being prepared: its build, release type and copyright, its texts in every language, its notes
 * for App Review, and its screenshots. A version in review or on sale can't change: then only how it stands is told.
 */
class VersionSync(
    private val api: AppStoreApi,
    private val listing: AppStoreListing,
    private val localizations: Localizations,
    private val screenshots: Map<String, List<File>>,
    private val builds: BuildChoice,
    private val report: AppStoreReport,
) {
    /** Brings the version being prepared up to date, if there is one. */
    fun sync(app: AppRecord) {
        val version =
            api
                .getAll("/v1/apps/${app.id}/appStoreVersions?filter[platform]=IOS&limit=20")
                .firstOrNull { it.attributes.string("appVersionState") in EDITABLE }
        if (version == null) {
            report.fact("No App Store version is being prepared: the listing's version texts wait for the next one")
            return
        }
        report.fact(
            "Version ${version.attributes.string("versionString")}: ${version.attributes.string("appVersionState")}",
        )
        report.step("Build") { build(app, version) }
        report.step("Release type and copyright") { details(version) }
        var ids = emptyMap<String, String>()
        report.step("Description, keywords and promotional text") { ids = texts(version) }
        report.step("App Review") { review(version) }
        ids[app.primaryLocale]?.let { primary -> ScreenshotSync(api, report).sync(primary, screenshots) }
    }

    private fun build(
        app: AppRecord,
        version: JsonObject,
    ) {
        val (build, versionString) =
            builds.pick(api, app) ?: run {
                report.owner("No build is processed yet: the version gets one once TestFlight has it")
                return
            }
        if (version.attributes.string("versionString") != versionString) {
            api.patch(
                "/v1/appStoreVersions/${version.id}",
                update(VERSIONS, version.id, mapOf("versionString" to JsonPrimitive(versionString))),
            )
            report.done("Version number: $versionString, the build's")
        }
        val current = api.get("/v1/appStoreVersions/${version.id}/relationships/build")?.resource?.string("id")
        if (current != build.id) {
            api.patch("/v1/appStoreVersions/${version.id}/relationships/build", link("builds", build.id))
            report.done("Build: $versionString (${build.attributes.string("version")})")
        }
        report.fact("Build: $versionString (${build.attributes.string("version")})")
        if (build.attributes["usesNonExemptEncryption"]?.jsonPrimitive?.booleanOrNull == null) {
            report.owner("Export compliance of build ${build.attributes.string("version")}: answer it in TestFlight")
        }
    }

    private fun details(version: JsonObject) {
        val wanted =
            listOfNotNull(
                listing.setting("releaseType")?.let { "releaseType" to JsonPrimitive(it) },
                listing.setting("copyright")?.let { "copyright" to JsonPrimitive(it) },
            ).toMap()
        val changed = changes(version.attributes, wanted)
        if (changed.isNotEmpty()) {
            api.patch("/v1/appStoreVersions/${version.id}", update(VERSIONS, version.id, changed))
            report.done(
                "Version: ${changed.entries.joinToString { (name, value) ->
                    "$name ${value.jsonPrimitive.content}"
                }}",
            )
        }
        if (version.attributes.string("copyright").isNullOrBlank() && "copyright" !in wanted) {
            report.owner("Copyright (the version page): the year and the rights holder's name, e.g. \"2026 Jane Doe\"")
        }
    }

    private fun texts(version: JsonObject): Map<String, String> {
        val urls =
            listOfNotNull(
                listing.setting("supportUrl")?.let { "supportUrl" to it },
                listing.setting("marketingUrl")?.let { "marketingUrl" to it },
            ).toMap()
        val wanted =
            Localizations
                .attributes(
                    listing,
                    mapOf(
                        ListingText.DESCRIPTION to "description",
                        ListingText.KEYWORDS to "keywords",
                        ListingText.PROMOTIONAL_TEXT to "promotionalText",
                    ),
                    urls,
                ).filterValues { "description" in it }
        return localizations.sync(
            "Description, keywords and promotional text",
            "/v1/appStoreVersions/${version.id}/appStoreVersionLocalizations?limit=200",
            "appStoreVersionLocalizations",
            "appStoreVersion" to link(VERSIONS, version.id),
            wanted,
        )
    }

    private fun review(version: JsonObject) {
        val wanted: Map<String, JsonElement> =
            listing.group("review.").mapValues { (_, it) -> value(it) } +
                listOfNotNull(listing.reviewNotes?.let { "notes" to JsonPrimitive(it) })
        val detail = api.get("/v1/appStoreVersions/${version.id}/appStoreReviewDetail")?.resource
        val attributes = JsonObject(detail?.attributes.orEmpty() + wanted)
        val missing = CONTACT.filter { attributes.string(it.key).isNullOrBlank() }.values
        if (detail != null) {
            val changed = changes(detail.attributes, wanted)
            if (changed.isNotEmpty()) {
                api.patch("/v1/appStoreReviewDetails/${detail.id}", update("appStoreReviewDetails", detail.id, changed))
                report.done("App Review information: ${changed.keys.joinToString()}")
            }
        } else if (missing.isEmpty()) {
            api.post(
                "/v1/appStoreReviewDetails",
                create("appStoreReviewDetails", wanted, mapOf("appStoreVersion" to link(VERSIONS, version.id))),
            )
            report.done("App Review information: ${wanted.keys.joinToString()}")
        }
        if (missing.isNotEmpty()) {
            // App Store Connect makes a version's App Review details only with the whole contact: until the owner
            // gives it, the notes and the rest wait for the next run.
            val later = if (detail == null) "; the notes for App Review go in at the next run after it" else ""
            report.owner(
                "App Review contact (the version page → App Review Information): ${missing.joinToString()}$later",
            )
        }
    }

    private companion object {
        const val VERSIONS = "appStoreVersions"

        /** The states in which a version can still change. */
        val EDITABLE =
            setOf("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY")

        /** The contact App Review needs, by attribute: the owner's own, so never in a repository. */
        val CONTACT =
            mapOf(
                "contactFirstName" to "first name",
                "contactLastName" to "last name",
                "contactPhone" to "phone number",
                "contactEmail" to "email",
            )
    }
}
