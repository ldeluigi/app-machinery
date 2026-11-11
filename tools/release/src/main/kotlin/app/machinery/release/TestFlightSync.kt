package app.machinery.release

import app.machinery.release.AppStoreDocuments.create
import app.machinery.release.AppStoreDocuments.link
import app.machinery.release.AppStoreDocuments.links
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * TestFlight: its texts (the beta description, the feedback address, the privacy policy) and an internal group, with
 * access to every build, that holds the team members of the listing's `testflight.internalTesterRoles`.
 */
class TestFlightSync(
    private val api: AppStoreApi,
    private val listing: AppStoreListing,
    private val localizations: Localizations,
    private val report: AppStoreReport,
) {
    /** Brings TestFlight's texts and its internal group up to date. */
    fun sync(app: AppRecord) {
        report.step("TestFlight texts") { texts(app) }
        report.step("TestFlight internal group") { internalGroup(app) }
    }

    private fun texts(app: AppRecord) {
        val extra =
            listOfNotNull(
                listing.setting("testflight.feedbackEmail")?.let { "feedbackEmail" to it },
                listing.setting("privacyPolicyUrl")?.let { "privacyPolicyUrl" to it },
                listing.setting("marketingUrl")?.let { "marketingUrl" to it },
            ).toMap()
        val wanted =
            Localizations
                .attributes(listing, mapOf(ListingText.BETA_DESCRIPTION to "description"), extra)
                .filterValues { "description" in it }
        if (wanted.isEmpty()) return
        localizations.sync(
            "TestFlight's test information",
            "/v1/apps/${app.id}/betaAppLocalizations?limit=200",
            "betaAppLocalizations",
            "app" to link("apps", app.id),
            wanted,
        )
    }

    private fun internalGroup(app: AppRecord) {
        val name = listing.setting("testflight.internalGroup") ?: return
        val groups = api.getAll("/v1/apps/${app.id}/betaGroups?limit=200")
        val internal = groups.filter { it.attributes["isInternalGroup"]?.jsonPrimitive?.booleanOrNull == true }
        val group =
            internal.firstOrNull { it.attributes.string("name") == name }?.id
                ?: internal.firstOrNull()?.id
                ?: checkNotNull(
                    api
                        .post(
                            "/v1/betaGroups",
                            create(
                                "betaGroups",
                                mapOf(
                                    "name" to JsonPrimitive(name),
                                    "isInternalGroup" to JsonPrimitive(true),
                                    "hasAccessToAllBuilds" to JsonPrimitive(true),
                                ),
                                mapOf("app" to link("apps", app.id)),
                            ),
                        ).resource,
                ).id.also { report.done("TestFlight: the internal group \"$name\", with every build") }
        val roles = listing.list("testflight.internalTesterRoles")
        if (roles.isEmpty()) return
        val users = api.getAll("/v1/users?filter[roles]=${roles.joinToString(",")}&limit=200")
        val testers =
            api
                .getAll("/v1/betaGroups/$group/betaTesters?limit=200")
                .mapNotNull { it.attributes.string("email")?.lowercase() }
                .toSet()
        val missing = users.filter { it.attributes.string("username")?.lowercase() !in testers }
        missing.forEach { user -> addTester(app, group, user.attributes) }
        if (missing.isNotEmpty()) {
            report.done(
                "TestFlight: ${missing.size} team member(s) (${roles.joinToString()}) invited to the internal group",
            )
        }
        report.fact("TestFlight internal group: ${testers.size + missing.size} tester(s)")
    }

    /** Adds the team member of [user]'s attributes to [group]: a new tester, or one the app already has. */
    private fun addTester(
        app: AppRecord,
        group: String,
        user: kotlinx.serialization.json.JsonObject,
    ) {
        val email = checkNotNull(user.string("username")) { "A team member without an Apple Account" }
        val known =
            api
                .getAll("/v1/betaTesters?filter[email]=${AppStoreSync.encode(email)}&filter[apps]=${app.id}&limit=1")
                .firstOrNull()
        if (known != null) {
            api.post("/v1/betaGroups/$group/relationships/betaTesters", links("betaTesters", listOf(known.id)))
            return
        }
        val attributes: Map<String, JsonElement> =
            listOf("firstName", "lastName")
                .mapNotNull { name -> user.string(name)?.let { name to JsonPrimitive(it) } }
                .toMap() + ("email" to JsonPrimitive(email))
        api.post(
            "/v1/betaTesters",
            create(
                "betaTesters",
                attributes,
                mapOf("betaGroups" to links("betaGroups", listOf(group))),
            ),
        )
    }
}
