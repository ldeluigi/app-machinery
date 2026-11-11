package app.machinery.release

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory

private const val APPS = "/v1/apps?filter[bundleId]=party.test&limit=200"
private const val INFOS =
    "/v1/apps/A1/appInfos?include=primaryCategory,primarySubcategoryOne,primarySubcategoryTwo,secondaryCategory," +
        "secondarySubcategoryOne,secondarySubcategoryTwo"
private const val RATING = "/v1/appInfos/I1/ageRatingDeclaration"
private const val INFO_TEXTS = "/v1/appInfos/I1/appInfoLocalizations?limit=200"
private const val SCHEDULE = "/v1/apps/A1/appPriceSchedule"
private const val POINTS = "/v1/apps/A1/appPricePoints?filter[territory]=USA&limit=200"
private const val MANUAL_PRICES =
    "/v1/appPriceSchedules/PS1/manualPrices?filter[territory]=USA&include=appPricePoint&limit=50"
private const val AVAILABILITY = "/v1/apps/A1/appAvailabilityV2"
private const val TERRITORIES = "/v1/territories?limit=200"
private const val TERRITORY_AVAILABILITIES =
    "/v2/appAvailabilities/AV1/territoryAvailabilities?include=territory&limit=200"
private const val VERSIONS = "/v1/apps/A1/appStoreVersions?filter[platform]=IOS&limit=20"
private const val BUILDS = "/v1/builds?filter[app]=A1&filter[preReleaseVersion.platform]=IOS&include=preReleaseVersion"
private const val RELEASE_BUILD = "$BUILDS&filter[preReleaseVersion.version]=1.2.0&sort=-uploadedDate&limit=1"
private const val NEWEST_BUILD =
    "$BUILDS&filter[processingState]=VALID&filter[expired]=false&sort=-uploadedDate&limit=1"
private const val VERSION_BUILD = "/v1/appStoreVersions/V1/relationships/build"
private const val VERSION_TEXTS = "/v1/appStoreVersions/V1/appStoreVersionLocalizations?limit=200"
private const val REVIEW = "/v1/appStoreVersions/V1/appStoreReviewDetail"
private const val SETS =
    "/v1/appStoreVersionLocalizations/L1/appScreenshotSets?filter[screenshotDisplayType]=APP_IPHONE_67"
private const val SHOTS = "/v1/appScreenshotSets/S1/appScreenshots?limit=50"
private const val BETA_TEXTS = "/v1/apps/A1/betaAppLocalizations?limit=200"
private const val GROUPS = "/v1/apps/A1/betaGroups?limit=200"
private const val USERS = "/v1/users?filter[roles]=ACCOUNT_HOLDER,ADMIN&limit=200"
private const val TESTERS = "/v1/betaGroups/G1/betaTesters?limit=200"
private const val KNOWN_TESTER = "/v1/betaTesters?filter[email]=owner%40example.com&filter[apps]=A1&limit=1"

private const val DESCRIPTION = "Example is a party game.\\n\\nPlay it."

/** A record just made in App Store Connect: one version 1.0 being prepared, nothing filled in. */
private fun FakeAppStore.fresh(screenshot: File) {
    documents[APPS] =
        document("""{"type":"apps","id":"A1","attributes":{"bundleId":"party.test","primaryLocale":"en-US"}}""")
    documents[INFOS] =
        document(
            """{"type":"appInfos","id":"I1","attributes":{"state":"PREPARE_FOR_SUBMISSION"},
            "relationships":{"primaryCategory":{"data":null}}}""",
        )
    documents[RATING] =
        single("""{"type":"ageRatingDeclarations","id":"D1","attributes":{"profanityOrCrudeHumor":null}}""")
    documents[INFO_TEXTS] =
        document("""{"type":"appInfoLocalizations","id":"IL1","attributes":{"locale":"en-US","name":"Example"}}""")
    documents[SCHEDULE] = null
    documents[POINTS] =
        document(
            """{"type":"appPricePoints","id":"P99","attributes":{"customerPrice":"0.99"}}""",
            """{"type":"appPricePoints","id":"P0","attributes":{"customerPrice":"0.0"}}""",
        )
    documents[AVAILABILITY] = null
    documents[TERRITORIES] = document("""{"type":"territories","id":"USA"}""", """{"type":"territories","id":"CHN"}""")
    documents[VERSIONS] =
        document(
            """{"type":"appStoreVersions","id":"V1","attributes":{"versionString":"1.0",
            "appVersionState":"PREPARE_FOR_SUBMISSION","copyright":null}}""",
        )
    documents[RELEASE_BUILD] =
        document(
            """{"type":"builds","id":"B1","attributes":{"version":"1002000","processingState":"VALID",
            "usesNonExemptEncryption":false}}""",
        )
    documents[VERSION_BUILD] = json("""{"data":null}""")
    documents[VERSION_TEXTS] =
        document("""{"type":"appStoreVersionLocalizations","id":"L1","attributes":{"locale":"en-US"}}""")
    created["/v1/appStoreVersionLocalizations"] = single("""{"type":"appStoreVersionLocalizations","id":"L2"}""")
    documents[REVIEW] = null
    documents[SETS] = document()
    created["/v1/appScreenshotSets"] = single("""{"type":"appScreenshotSets","id":"S1"}""")
    created["/v1/appScreenshots"] =
        single(
            """{"type":"appScreenshots","id":"SS1","attributes":{"uploadOperations":[
            {"method":"PUT","url":"https://upload.example/1","offset":0,"length":${screenshot.length()},
            "requestHeaders":[{"name":"Content-Type","value":"image/png"}]}]}}""",
        )
    documents["/v1/appScreenshots/SS1"] =
        single("""{"type":"appScreenshots","id":"SS1","attributes":{"assetDeliveryState":{"state":"COMPLETE"}}}""")
    documents[BETA_TEXTS] = document()
    documents[GROUPS] = document()
    created["/v1/betaGroups"] = single("""{"type":"betaGroups","id":"G1"}""")
    documents[USERS] =
        document(
            """{"type":"users","id":"U1","attributes":{"username":"owner@example.com","firstName":"O","lastName":"W",
            "roles":["ACCOUNT_HOLDER"]}}""",
        )
    documents[TESTERS] = document()
    documents[KNOWN_TESTER] = document()
}

/** The same record once a run filled it in: nothing is left to change. */
private fun FakeAppStore.filledIn(screenshot: File) {
    fresh(screenshot)
    documents[APPS] =
        document(
            """{"type":"apps","id":"A1","attributes":{"bundleId":"party.test","primaryLocale":"en-US",
            "contentRightsDeclaration":"USES_THIRD_PARTY_CONTENT"}}""",
        )
    documents[INFOS] =
        document(
            """{"type":"appInfos","id":"I1","attributes":{"state":"PREPARE_FOR_SUBMISSION","appStoreAgeRating":"NINE_PLUS"},
            "relationships":{"primaryCategory":{"data":{"type":"appCategories","id":"GAMES"}},
            "primarySubcategoryOne":{"data":{"type":"appCategories","id":"GAMES_PUZZLE"}}}}""",
        )
    documents[RATING] =
        single(
            """{"type":"ageRatingDeclarations","id":"D1","attributes":{"profanityOrCrudeHumor":"INFREQUENT",
            "unrestrictedWebAccess":false}}""",
        )
    documents[INFO_TEXTS] =
        document(
            """{"type":"appInfoLocalizations","id":"IL1","attributes":{"locale":"en-US","name":"Example",
            "subtitle":"A game for friends","privacyPolicyUrl":"https://example.com/privacy"}}""",
            """{"type":"appInfoLocalizations","id":"IL2","attributes":{"locale":"de-DE","name":"Example",
            "privacyPolicyUrl":"https://example.com/privacy"}}""",
        )
    documents[SCHEDULE] = single("""{"type":"appPriceSchedules","id":"PS1"}""")
    documents[MANUAL_PRICES] =
        json(
            """{"data":[{"type":"appPrices","id":"AP1","attributes":{"startDate":null,"endDate":null},
            "relationships":{"appPricePoint":{"data":{"type":"appPricePoints","id":"P0"}}}}],
            "included":[{"type":"appPricePoints","id":"P0","attributes":{"customerPrice":"0.0"}}]}""",
        )
    documents[AVAILABILITY] =
        single("""{"type":"appAvailabilities","id":"AV1","attributes":{"availableInNewTerritories":true}}""")
    documents[TERRITORY_AVAILABILITIES] =
        document(
            """{"type":"territoryAvailabilities","id":"T1","attributes":{"available":true,"contentStatuses":["AVAILABLE"]},
            "relationships":{"territory":{"data":{"type":"territories","id":"USA"}}}}""",
            """{"type":"territoryAvailabilities","id":"T2","attributes":{"available":false,"contentStatuses":[]},
            "relationships":{"territory":{"data":{"type":"territories","id":"CHN"}}}}""",
        )
    documents[VERSIONS] =
        document(
            """{"type":"appStoreVersions","id":"V1","attributes":{"versionString":"1.2.0",
            "appVersionState":"PREPARE_FOR_SUBMISSION","releaseType":"MANUAL","copyright":"2026 Someone"}}""",
        )
    documents[VERSION_BUILD] = single("""{"type":"builds","id":"B1"}""")
    val urls = """"supportUrl":"https://example.com/support""""
    documents[VERSION_TEXTS] =
        document(
            """{"type":"appStoreVersionLocalizations","id":"L1","attributes":{"locale":"en-US",
            "description":"$DESCRIPTION","keywords":"party,friends","promotionalText":"Now with stars.",$urls}}""",
            """{"type":"appStoreVersionLocalizations","id":"L2","attributes":{"locale":"de-DE",
            "description":"Example ist ein Partyspiel.",$urls}}""",
        )
    documents[REVIEW] =
        single(
            """{"type":"appStoreReviewDetails","id":"R1","attributes":{"contactFirstName":"O","contactLastName":"W",
            "contactPhone":"+1 555","contactEmail":"support@example.com","demoAccountRequired":false,
            "notes":"No account needed."}}""",
        )
    documents[SETS] = document("""{"type":"appScreenshotSets","id":"S1"}""")
    documents[SHOTS] =
        document(
            """{"type":"appScreenshots","id":"SS1","attributes":{"sourceFileChecksum":"${ScreenshotSync.md5(
                screenshot,
            )}",
            "assetDeliveryState":{"state":"COMPLETE"}}}""",
        )
    documents[BETA_TEXTS] =
        document(
            """{"type":"betaAppLocalizations","id":"BL1","attributes":{"locale":"en-US",
            "description":"Test builds of Example.","feedbackEmail":"support@example.com",
            "privacyPolicyUrl":"https://example.com/privacy"}}""",
        )
    documents[GROUPS] =
        document("""{"type":"betaGroups","id":"G1","attributes":{"name":"Team","isInternalGroup":true}}""")
    documents[TESTERS] = document("""{"type":"betaTesters","id":"BT1","attributes":{"email":"Owner@example.com"}}""")
}

class AppStoreSyncTest :
    FunSpec({
        val dir = createTempDirectory("app-store").toFile()
        afterSpec { dir.deleteRecursively() }
        val listing = AppStoreListing.read(listingIn(dir.resolve("listing")), "Example")
        val screenshot = dir.resolve("1-home.png").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        val screenshots = mapOf("APP_IPHONE_67" to listOf(screenshot))

        fun run(
            api: FakeAppStore,
            builds: BuildChoice = BuildChoice("1.2.0"),
        ): AppStoreReport =
            AppStoreReport().also { AppStoreSync(api, listing, screenshots, builds, it).run("party.test") }

        test("a new record gets everything the listing has, and nothing is submitted") {
            val api = FakeAppStore().apply { fresh(screenshot) }
            val report = run(api)
            report.markdown() shouldNotContain "### Failed"
            api.writes shouldContainExactly
                listOf(
                    "PATCH /v1/apps/A1",
                    "PATCH /v1/appInfos/I1",
                    "PATCH /v1/ageRatingDeclarations/D1",
                    "POST /v1/appInfoLocalizations",
                    "PATCH /v1/appInfoLocalizations/IL1",
                    "POST /v1/appPriceSchedules",
                    "POST /v2/appAvailabilities",
                    "PATCH /v1/appStoreVersions/V1",
                    "PATCH /v1/appStoreVersions/V1/relationships/build",
                    "PATCH /v1/appStoreVersions/V1",
                    "POST /v1/appStoreVersionLocalizations",
                    "PATCH /v1/appStoreVersionLocalizations/L1",
                    "POST /v1/appStoreReviewDetails",
                    "POST /v1/appScreenshotSets",
                    "POST /v1/appScreenshots",
                    "UPLOAD https://upload.example/1",
                    "PATCH /v1/appScreenshots/SS1",
                    "POST /v1/betaAppLocalizations",
                    "POST /v1/betaGroups",
                    "POST /v1/betaTesters",
                )
            api.writes.filter { "reviewSubmission" in it }.shouldBeEmpty()
            api.uploads["https://upload.example/1"] shouldBe screenshot.readBytes()
        }

        test("the requests say what App Store Connect expects") {
            val api = FakeAppStore().apply { fresh(screenshot) }
            run(api)
            val rating = api.body("PATCH /v1/ageRatingDeclarations/D1")["data"]!!.jsonObject.attributes
            rating["profanityOrCrudeHumor"] shouldBe JsonPrimitive("INFREQUENT")
            rating["unrestrictedWebAccess"] shouldBe JsonPrimitive(false)
            val price = api.body("POST /v1/appPriceSchedules")
            price.toString() shouldContain """{"type":"appPricePoints","id":"P0"}"""
            price.toString() shouldContain """"baseTerritory":{"data":{"type":"territories","id":"USA"}}"""
            val availability = api.body("POST /v2/appAvailabilities")["included"]!!.jsonArray.map { it.jsonObject }
            availability.associate {
                it.related(
                    "territory",
                ) to it.attributes["available"]!!.jsonPrimitive.content
            } shouldBe
                mapOf("USA" to "true", "CHN" to "false")
            val english = api.body("PATCH /v1/appStoreVersionLocalizations/L1")["data"]!!.jsonObject.attributes
            english.keys shouldContainExactly setOf("description", "keywords", "promotionalText", "supportUrl")
            english["description"]!!.jsonPrimitive.content shouldBe "Example is a party game.\n\nPlay it."
            val german = api.body("POST /v1/appStoreVersionLocalizations")["data"]!!.jsonObject
            german.attributes["locale"] shouldBe JsonPrimitive("de-DE")
            german.related("appStoreVersion") shouldBe "V1"
            api.calls.filter { it.first == "PATCH /v1/appStoreVersions/V1" }.map { it.second.toString() } shouldBe
                listOf(
                    """{"data":{"type":"appStoreVersions","id":"V1","attributes":{"versionString":"1.2.0"}}}""",
                    """{"data":{"type":"appStoreVersions","id":"V1","attributes":{"releaseType":"MANUAL"}}}""",
                )
            api.body("PATCH /v1/appStoreVersions/V1/relationships/build").toString() shouldBe
                """{"data":{"type":"builds","id":"B1"}}"""
            api.body("PATCH /v1/appScreenshots/SS1")["data"]!!.jsonObject.attributes["sourceFileChecksum"] shouldBe
                JsonPrimitive(ScreenshotSync.md5(screenshot))
            val tester = api.body("POST /v1/betaTesters")["data"]!!.jsonObject
            tester.attributes["email"] shouldBe JsonPrimitive("owner@example.com")
            tester.toString() shouldContain """"betaGroups":{"data":[{"type":"betaGroups","id":"G1"}]}"""
        }

        test("the owner is told what only they can give, without anyone's details") {
            val api = FakeAppStore().apply { fresh(screenshot) }
            val summary = run(api).markdown()
            summary shouldContain
                "App Review contact (the version page → App Review Information): first name, last name, phone number"
            summary shouldContain "Copyright"
            summary shouldContain "App Privacy"
            summary shouldNotContain "owner@example.com"
        }

        test("a record that is up to date gets no change at all") {
            val api = FakeAppStore().apply { filledIn(screenshot) }
            val report = run(api)
            report.failed shouldBe false
            api.writes.shouldBeEmpty()
            report.markdown() shouldContain "Nothing: the listing was already up to date."
            report.markdown() shouldNotContain "App Review contact"
        }

        test("changed screenshots replace the whole set") {
            val api =
                FakeAppStore().apply {
                    filledIn(screenshot)
                    documents[SHOTS] =
                        document(
                            """{"type":"appScreenshots","id":"OLD","attributes":{"sourceFileChecksum":"0000",
                            "assetDeliveryState":{"state":"COMPLETE"}}}""",
                        )
                }
            run(api)
            api.writes shouldContainExactly
                listOf(
                    "DELETE /v1/appScreenshots/OLD",
                    "POST /v1/appScreenshots",
                    "UPLOAD https://upload.example/1",
                    "PATCH /v1/appScreenshots/SS1",
                )
        }

        test("a version in review and app information on sale are left alone") {
            val api =
                FakeAppStore().apply {
                    filledIn(screenshot)
                    documents[INFOS] =
                        document("""{"type":"appInfos","id":"I1","attributes":{"state":"READY_FOR_DISTRIBUTION"}}""")
                    documents[VERSIONS] =
                        document(
                            """{"type":"appStoreVersions","id":"V1","attributes":{"versionString":"1.2.0",
                            "appVersionState":"WAITING_FOR_REVIEW"}}""",
                        )
                }
            val report = run(api)
            api.writes.shouldBeEmpty()
            report.markdown() shouldContain "No App Store version is being prepared"
            report.markdown() shouldContain "The app information is READY_FOR_DISTRIBUTION"
        }

        test("a failed step doesn't stop the others, and fails the run") {
            val api =
                FakeAppStore().apply {
                    fresh(screenshot)
                    failing += "PATCH /v1/apps/A1"
                }
            val report = run(api)
            report.failed shouldBe true
            report.markdown() shouldContain "Content rights: App Store Connect answered 409 to PATCH /v1/apps/A1"
            api.writes shouldContain "POST /v1/betaTesters"
        }

        test("territories where the app can't be sold yet are listed with Apple's reason") {
            val api =
                FakeAppStore().apply {
                    filledIn(screenshot)
                    documents[TERRITORY_AVAILABILITIES] =
                        document(
                            """{"type":"territoryAvailabilities","id":"T1","attributes":{"available":true,
                            "contentStatuses":["TRADER_STATUS_NOT_PROVIDED"]},
                            "relationships":{"territory":{"data":{"type":"territories","id":"ITA"}}}}""",
                            """{"type":"territoryAvailabilities","id":"T3","attributes":{"available":true,
                            "contentStatuses":["TRADER_STATUS_NOT_PROVIDED"]},
                            "relationships":{"territory":{"data":{"type":"territories","id":"DEU"}}}}""",
                        )
                }
            run(api).markdown() shouldContain "Can't be sold in DEU, ITA: TRADER_STATUS_NOT_PROVIDED"
        }

        test("a team member who already tests the app joins the group as they are") {
            val api =
                FakeAppStore().apply {
                    fresh(screenshot)
                    documents[KNOWN_TESTER] = document("""{"type":"betaTesters","id":"BT9"}""")
                }
            run(api)
            api.body("POST /v1/betaGroups/G1/relationships/betaTesters").toString() shouldBe
                """{"data":[{"type":"betaTesters","id":"BT9"}]}"""
            api.writes.filter { it == "POST /v1/betaTesters" }.shouldBeEmpty()
        }

        test("without the release's build yet, the newest processed one goes in") {
            val api =
                FakeAppStore().apply {
                    fresh(screenshot)
                    documents[RELEASE_BUILD] = document()
                    documents[NEWEST_BUILD] =
                        json(
                            """{"data":[{"type":"builds","id":"B0","attributes":{"version":"1001000",
                            "processingState":"VALID","usesNonExemptEncryption":false},
                            "relationships":{"preReleaseVersion":{"data":{"type":"preReleaseVersions","id":"PV1"}}}}],
                            "included":[{"type":"preReleaseVersions","id":"PV1","attributes":{"version":"1.1.0"}}]}""",
                        )
                }
            val report = run(api)
            api.calls
                .first { it.first == "PATCH /v1/appStoreVersions/V1" }
                .second
                .toString() shouldContain "1.1.0"
            api.body("PATCH /v1/appStoreVersions/V1/relationships/build").toString() shouldContain "B0"
            report.markdown() shouldContain "Build: 1.1.0 (1001000)"
        }

        test("the release's build is waited for while App Store Connect processes it") {
            val api = FakeAppStore().apply { fresh(screenshot) }
            val processing =
                document(
                    """{"type":"builds","id":"B1","attributes":{"version":"1002000","processingState":"PROCESSING"}}""",
                )
            val valid = api.documents.getValue(RELEASE_BUILD)
            var polls = 0
            val waits = mutableListOf<java.time.Duration>()
            api.documents[RELEASE_BUILD] = processing
            val builds =
                BuildChoice(
                    "1.2.0",
                    wait = java.time.Duration.ofMinutes(5),
                    sleep = {
                        waits += it
                        if (++polls == 2) api.documents[RELEASE_BUILD] = valid
                    },
                )
            run(api, builds)
            waits.size shouldBe 2
            api.body("PATCH /v1/appStoreVersions/V1/relationships/build").toString() shouldContain "B1"
        }

        test("an app App Store Connect doesn't know stops the run with what to do") {
            val api = FakeAppStore().apply { documents[APPS] = document() }
            val report = run(api)
            report.failed shouldBe true
            report.markdown() shouldContain "no app with the bundle id party.test: make its record first"
            api.calls.map { it.first } shouldContainAll listOf("GET $APPS")
        }
    })
