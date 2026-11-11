package app.machinery.release

import app.machinery.release.AppStoreDocuments.changes
import app.machinery.release.AppStoreDocuments.create
import app.machinery.release.AppStoreDocuments.link
import app.machinery.release.AppStoreDocuments.update
import app.machinery.release.AppStoreDocuments.value
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/** The app's record in App Store Connect, as the tool finds it. */
data class AppRecord(
    val id: String,
    val primaryLocale: String,
    val attributes: JsonObject,
)

/**
 * Brings an app's record in App Store Connect in line with its [listing]: the app information, the price and the
 * countries, the version being prepared (its build, texts, App Review notes and [screenshots]) and TestFlight, whose
 * external groups get the build with the release's [notes] (by Google Play's language codes). Every step runs,
 * whatever the others do, and the [report] tells what changed, how things stand, and what's left for the owner. No
 * version is ever submitted to App Review or released; builds for external testers go to Beta App Review.
 */
class AppStoreSync(
    private val api: AppStoreApi,
    private val listing: AppStoreListing,
    private val screenshots: Map<String, List<java.io.File>>,
    private val builds: BuildChoice,
    private val report: AppStoreReport,
    private val notes: Map<String, String> = emptyMap(),
) {
    /** Runs every step for the app of [bundleId]. */
    fun run(bundleId: String) {
        val app = findApp(bundleId)
        if (app == null) {
            report.step("The app's record") {
                error("App Store Connect has no app with the bundle id $bundleId: make its record first")
            }
            return
        }
        val localizations = Localizations(api, report)
        report.step("Content rights") { contentRights(app) }
        report.step("App information") { AppInfoSync(api, listing, localizations, report).sync(app) }
        val pricing = PricingSync(api, listing, report)
        report.step("Price") { pricing.price(app) }
        report.step("Availability") { pricing.availability(app) }
        VersionSync(api, listing, localizations, screenshots, builds, report).sync(app)
        TestFlightSync(api, listing, localizations, report).sync(app)
        ExternalTesting(api, listing, localizations, builds, notes, report).sync(app)
        UNCHECKABLE.forEach(report::owner)
    }

    private fun findApp(bundleId: String): AppRecord? =
        api
            .getAll("/v1/apps?filter[bundleId]=${encode(bundleId)}&limit=200")
            .firstOrNull { it.attributes.string("bundleId") == bundleId }
            ?.let { AppRecord(it.id, it.attributes.string("primaryLocale") ?: DEFAULT_LOCALE, it.attributes) }

    private fun contentRights(app: AppRecord) {
        val wanted = listing.setting("contentRightsDeclaration") ?: return
        if (app.attributes.string("contentRightsDeclaration") == wanted) return
        api.patch(
            "/v1/apps/${app.id}",
            update("apps", app.id, mapOf("contentRightsDeclaration" to JsonPrimitive(wanted))),
        )
        report.done("Content rights: $wanted")
    }

    companion object {
        /** The language of an app whose record doesn't say. */
        const val DEFAULT_LOCALE = "en-US"

        /** What App Store Connect's API can neither answer nor read back, so each run reminds the owner of it. */
        val UNCHECKABLE =
            listOf(
                "App Privacy (App Store Connect → the app → App Privacy): the API can't answer it, nor tell whether " +
                    "it is answered. Answer it as the privacy policy says, then Publish.",
            )

        /** [text] for a URL's query. */
        fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)
    }
}

/**
 * The localizations of one resource of App Store Connect (the app information, a version, TestFlight's texts): the
 * missing ones are created, the others changed where they differ from the listing.
 */
class Localizations(
    private val api: AppStoreApi,
    private val report: AppStoreReport,
) {
    /**
     * Makes the localizations listed at [path], of [type], match [wanted] (attributes by locale); new ones point to
     * their [parent] (relationship name to its linkage). Returns every localization's id by locale.
     */
    fun sync(
        what: String,
        path: String,
        type: String,
        parent: Pair<String, JsonObject>,
        wanted: Map<String, Map<String, JsonElement>>,
    ): Map<String, String> {
        val existing = api.getAll(path).associateBy { it.attributes.string("locale").orEmpty() }
        val created = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val ids =
            wanted.mapValues { (locale, attributes) ->
                val current = existing[locale]
                if (current == null) {
                    created += locale
                    api
                        .post(
                            "/v1/$type",
                            create(type, attributes + ("locale" to JsonPrimitive(locale)), mapOf(parent)),
                        ).resource
                        ?.id
                } else {
                    val changes = changes(current.attributes, attributes)
                    if (changes.isNotEmpty()) {
                        changed += locale
                        api.patch("/v1/$type/${current.id}", update(type, current.id, changes))
                    }
                    current.id
                }
            }
        if (created.isNotEmpty()) report.done("$what: added ${created.joinToString()}")
        if (changed.isNotEmpty()) report.done("$what: updated ${changed.joinToString()}")
        return existing.mapValues { it.value.id } + ids.filterValues { it != null }.mapValues { it.value.orEmpty() }
    }

    companion object {
        /** The [ListingText]s of every language of [listing] as attributes named by [names], with [extra] in each. */
        fun attributes(
            listing: AppStoreListing,
            names: Map<ListingText, String>,
            extra: Map<String, String>,
        ): Map<String, Map<String, JsonElement>> =
            listing.texts
                .mapValues { (_, texts) ->
                    names.mapNotNull { (text, name) -> texts[text]?.let { name to JsonPrimitive(it) } }.toMap()
                }.filterValues { it.isNotEmpty() }
                .mapValues { (_, attributes) -> attributes + extra.mapValues { JsonPrimitive(it.value) } }
    }
}

/** The app information: its categories, its age rating's answers, and its name, subtitle and privacy policy. */
class AppInfoSync(
    private val api: AppStoreApi,
    private val listing: AppStoreListing,
    private val localizations: Localizations,
    private val report: AppStoreReport,
) {
    /** Brings the app information that can still change (the one of the version being prepared) up to date. */
    fun sync(app: AppRecord) {
        val infos = api.getAll("/v1/apps/${app.id}/appInfos?include=${CATEGORIES.joinToString(",")}")
        val info = infos.firstOrNull { it.attributes.string("state") in EDITABLE }
        if (info == null) {
            val states = infos.mapNotNull { it.attributes.string("state") }.joinToString()
            report.fact("The app information is $states: it changes with the next version")
            return
        }
        report.fact("Age rating: ${info.attributes.string("appStoreAgeRating") ?: "not calculated yet"}")
        report.step("Categories") { categories(info) }
        report.step("Age rating") { ageRating(info) }
        report.step("Name and subtitle") {
            val wanted =
                Localizations.attributes(
                    listing,
                    mapOf(ListingText.NAME to "name", ListingText.SUBTITLE to "subtitle"),
                    listOfNotNull(
                        listing.setting("privacyPolicyUrl")?.let { "privacyPolicyUrl" to it },
                        listing.setting("privacyChoicesUrl")?.let { "privacyChoicesUrl" to it },
                    ).toMap(),
                )
            localizations.sync(
                "Name, subtitle and privacy policy",
                "/v1/appInfos/${info.id}/appInfoLocalizations?limit=200",
                "appInfoLocalizations",
                "appInfo" to link("appInfos", info.id),
                wanted,
            )
        }
    }

    private fun categories(info: JsonObject) {
        val wanted = CATEGORIES.mapNotNull { name -> listing.setting(name)?.let { name to it } }.toMap()
        if (wanted.all { (name, category) -> info.related(name) == category }) return
        // All of them, even those that stay: a subcategory goes with its category
        api.patch(
            "/v1/appInfos/${info.id}",
            update(
                "appInfos",
                info.id,
                relationships =
                    wanted.mapValues { (_, category) ->
                        link("appCategories", category)
                    },
            ),
        )
        report.done("Categories: ${wanted.values.joinToString()}")
    }

    private fun ageRating(info: JsonObject) {
        val answers = listing.group("ageRating.").mapValues { (_, answer) -> value(answer) }
        if (answers.isEmpty()) return
        val declaration =
            checkNotNull(api.get("/v1/appInfos/${info.id}/ageRatingDeclaration")?.resource) {
                "The app information has no age rating declaration"
            }
        val changed = changes(declaration.attributes, answers)
        if (changed.isEmpty()) return
        api.patch(
            "/v1/ageRatingDeclarations/${declaration.id}",
            update("ageRatingDeclarations", declaration.id, changed),
        )
        report.done("Age rating answers: ${changed.keys.joinToString()}")
    }

    companion object {
        /** The app information's relationships to its categories, as the listing's settings name them too. */
        val CATEGORIES =
            listOf(
                "primaryCategory",
                "primarySubcategoryOne",
                "primarySubcategoryTwo",
                "secondaryCategory",
                "secondarySubcategoryOne",
                "secondarySubcategoryTwo",
            )

        /** The states in which the app information can change. */
        val EDITABLE = setOf("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "READY_FOR_REVIEW")
    }
}
