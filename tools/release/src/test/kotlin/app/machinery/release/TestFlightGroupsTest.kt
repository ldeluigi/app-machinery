package app.machinery.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory

private const val DETAIL = "/v1/apps/A1/betaAppReviewDetail"
private const val GROUPS = "/v1/apps/A1/betaGroups?limit=200"
private const val BUILD =
    "/v1/builds?filter[app]=A1&filter[preReleaseVersion.platform]=IOS&include=preReleaseVersion" +
        "&filter[preReleaseVersion.version]=1.2.0&sort=-uploadedDate&limit=1"
private const val BETA_BUILDS = "/v1/betaGroups/G2/relationships/builds?limit=200"
private const val WHAT_TO_TEST = "/v1/builds/B1/betaBuildLocalizations?limit=200"
private const val BETA_DETAIL = "/v1/builds/B1/buildBetaDetail"

private const val CONTACT = """"contactFirstName":"O","contactLastName":"W","contactPhone":"+1 555""""

/** A listing with a public beta for every release and a group of friends who get builds chosen by hand. */
private fun groupsIn(dir: File): File =
    listingIn(dir).apply {
        resolve("en-US/what_to_test.txt").writeText("Play a whole party.")
        resolve(AppStoreListing.SETTINGS).appendText(
            """

            testflight.externalGroups=beta, friends
            testflight.externalGroup.beta.name=Public beta
            testflight.externalGroup.beta.publicLink=true
            testflight.externalGroup.beta.publicLinkLimit=500
            testflight.externalGroup.friends.name=Friends
            testflight.externalGroup.friends.builds=manual
            testflight.externalGroup.friends.feedback=false
            """.trimIndent(),
        )
    }

/** App Store Connect with the friends' group only, a processed build of 1.2.0, and the review's contact given. */
private fun FakeAppStore.withFriends() {
    documents[DETAIL] =
        single("""{"type":"betaAppReviewDetails","id":"R1","attributes":{$CONTACT,"contactEmail":null}}""")
    documents[GROUPS] =
        document(
            """{"type":"betaGroups","id":"G1","attributes":{"name":"Team","isInternalGroup":true}}""",
            """{"type":"betaGroups","id":"G3","attributes":{"name":"Friends","isInternalGroup":false,
            "feedbackEnabled":true,"publicLinkEnabled":false}}""",
        )
    created["/v1/betaGroups"] =
        single(
            """{"type":"betaGroups","id":"G2","attributes":{"name":"Public beta","isInternalGroup":false,
            "feedbackEnabled":true,"publicLinkEnabled":false}}""",
        )
    documents["/v1/betaGroups/G2"] =
        single(
            """{"type":"betaGroups","id":"G2","attributes":{"name":"Public beta",
            "publicLink":"https://testflight.apple.com/join/abc"}}""",
        )
    documents["/v1/betaGroups/G3"] = single("""{"type":"betaGroups","id":"G3","attributes":{"name":"Friends"}}""")
    documents[BUILD] =
        document("""{"type":"builds","id":"B1","attributes":{"version":"1002000","processingState":"VALID"}}""")
    documents[BETA_BUILDS] = document()
    documents[WHAT_TO_TEST] = document()
    documents[BETA_DETAIL] =
        single(
            """{"type":"buildBetaDetails","id":"B1","attributes":{"externalBuildState":"READY_FOR_BETA_SUBMISSION"}}""",
        )
}

class TestFlightGroupsTest :
    FunSpec({
        val dir = createTempDirectory("testflight").toFile()
        afterSpec { dir.deleteRecursively() }
        val listing = AppStoreListing.read(groupsIn(dir.resolve("listing")), "Example")
        val app = AppRecord("A1", "en-US", json("{}"))
        // As the release writes them for Google Play; German has no TestFlight texts in the listing
        val notes = mapOf("en-US" to "v1.2.0\n• Stars shine brighter ✨️\n• Fewer ⚠ waits 😀", "de-DE" to "v1.2.0")

        fun run(
            api: FakeAppStore,
            notes: Map<String, String> = emptyMap(),
        ): AppStoreReport =
            AppStoreReport().also { report ->
                ExternalTesting(api, listing, Localizations(api, report), BuildChoice("1.2.0"), notes, report).sync(app)
            }

        test("the groups are made as the listing has them, and the release's build goes to Beta App Review") {
            val api = FakeAppStore().apply { withFriends() }
            val report = run(api, notes)
            report.failed shouldBe false
            api.writes shouldContainExactly
                listOf(
                    "PATCH /v1/betaAppReviewDetails/R1",
                    "POST /v1/betaGroups",
                    "PATCH /v1/betaGroups/G2",
                    "PATCH /v1/betaGroups/G3",
                    "POST /v1/betaGroups/G2/relationships/builds",
                    "POST /v1/betaBuildLocalizations",
                    "POST /v1/betaAppReviewSubmissions",
                )
            val group = api.body("POST /v1/betaGroups")["data"]!!.jsonObject
            group.attributes["name"] shouldBe JsonPrimitive("Public beta")
            group.attributes["isInternalGroup"] shouldBe JsonPrimitive(false)
            group.related("app") shouldBe "A1"
            api.body("PATCH /v1/betaGroups/G2")["data"]!!.jsonObject.attributes shouldBe
                json("""{"publicLinkEnabled":true,"publicLinkLimitEnabled":true,"publicLinkLimit":500}""")
            api.body("PATCH /v1/betaGroups/G3")["data"]!!.jsonObject.attributes shouldBe
                json("""{"feedbackEnabled":false}""")
            val review = api.body("PATCH /v1/betaAppReviewDetails/R1")["data"]!!.jsonObject.attributes
            review["contactEmail"] shouldBe JsonPrimitive("support@example.com")
            review["notes"] shouldBe JsonPrimitive("No account needed.")
            // The API's name for What to Test, in characters App Store Connect takes
            val english = api.body("POST /v1/betaBuildLocalizations")["data"]!!.jsonObject.attributes
            english["locale"] shouldBe JsonPrimitive("en-US")
            english["whatsNew"] shouldBe
                JsonPrimitive("Play a whole party.\n\nv1.2.0\n- Stars shine brighter\n- Fewer  waits")
            api.body("POST /v1/betaAppReviewSubmissions")["data"]!!.jsonObject.related("build") shouldBe "B1"
            val summary = report.markdown()
            summary shouldContain "TestFlight group \"Public beta\": public link https://testflight.apple.com/join/abc"
            summary shouldContain "TestFlight group \"Friends\": no public link, builds chosen in App Store Connect"
            summary shouldContain "Build 1.2.0 (1002000) sent to Beta App Review"
        }

        test("without the review's contact, the build waits, and the owner is told") {
            val api =
                FakeAppStore().apply {
                    withFriends()
                    documents[DETAIL] = single("""{"type":"betaAppReviewDetails","id":"R1","attributes":{}}""")
                }
            val report = run(api, notes)
            api.writes shouldNotContain "POST /v1/betaAppReviewSubmissions"
            report.markdown() shouldContain
                "Beta App Review contact (TestFlight → Test Information): first name, last name, phone number"
            report.markdown() shouldNotContain "owner@example.com"
        }

        test("a run without the release's notes changes no What to Test, and a build in review stays as it is") {
            val api =
                FakeAppStore().apply {
                    withFriends()
                    documents[GROUPS] =
                        document(
                            """{"type":"betaGroups","id":"G2","attributes":{"name":"Public beta",
                            "isInternalGroup":false,"feedbackEnabled":true,"publicLinkEnabled":true,
                            "publicLinkLimitEnabled":true,"publicLinkLimit":500}}""",
                            """{"type":"betaGroups","id":"G3","attributes":{"name":"Friends","isInternalGroup":false,
                            "feedbackEnabled":false,"publicLinkEnabled":false}}""",
                        )
                    documents[DETAIL] =
                        single(
                            """{"type":"betaAppReviewDetails","id":"R1","attributes":{$CONTACT,
                            "contactEmail":"support@example.com","demoAccountRequired":false,
                            "notes":"No account needed."}}""",
                        )
                    documents[BETA_BUILDS] = document("""{"type":"builds","id":"B1"}""")
                    documents[WHAT_TO_TEST] =
                        document(
                            """{"type":"betaBuildLocalizations","id":"BB1","attributes":{"locale":"en-US",
                            "whatsNew":"Play a whole party.\n\nStars shine brighter."}}""",
                        )
                    documents[BETA_DETAIL] =
                        single(
                            """{"type":"buildBetaDetails","id":"B1",
                            "attributes":{"externalBuildState":"IN_BETA_REVIEW"}}""",
                        )
                }
            val report = run(api)
            api.writes shouldBe emptyList()
            report.markdown() shouldContain "Build 1.2.0 (1002000) for external testers: IN_BETA_REVIEW"
        }

        test("a public link App Store Connect refuses is left to the owner, without failing the run") {
            val api =
                FakeAppStore().apply {
                    withFriends()
                    failing += "PATCH /v1/betaGroups/G2"
                }
            val report = run(api, notes)
            report.failed shouldBe false
            report.markdown() shouldContain
                "TestFlight group \"Public beta\": App Store Connect refused publicLinkEnabled"
            api.writes.last() shouldBe "POST /v1/betaAppReviewSubmissions"
        }

        test("wrong group settings stop the job before it changes anything") {
            val wrong =
                listingIn(dir.resolve("wrong")).apply {
                    resolve(AppStoreListing.SETTINGS).appendText(
                        """

                        testflight.externalGroups=a,b
                        testflight.externalGroup.a.name=Beta
                        testflight.externalGroup.a.builds=sometimes
                        testflight.externalGroup.a.publicLinkLimit=20000
                        testflight.externalGroup.b.name=Beta
                        testflight.externalGroup.b.publicLink=yes
                        """.trimIndent(),
                    )
                }
            val error = shouldThrow<IllegalStateException> { AppStoreListing.read(wrong, "Example") }
            error.message shouldContain "testflight.externalGroup.a.builds: releases or manual"
            error.message shouldContain "testflight.externalGroup.a.publicLinkLimit: a number from 1 to 10000"
            error.message shouldContain "testflight.externalGroup.b.publicLink: true or false"
            error.message shouldContain "two groups are named \"Beta\""
        }
    })
