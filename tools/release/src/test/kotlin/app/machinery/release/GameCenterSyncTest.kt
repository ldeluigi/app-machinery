package app.machinery.release

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory

class GameCenterSyncTest :
    FunSpec({
        val root = createTempDirectory("game-center").toFile()
        afterSpec { root.deleteRecursively() }

        val pictures = "composeApp/build/store-art/achievements/game-center"
        val apps = "/v1/apps?filter[bundleId]=app.id&limit=200"
        val detail = "/v1/apps/1/gameCenterDetail"
        val list = "/v1/gameCenterDetails/gc/gameCenterAchievements?limit=200"
        val localizations = "/v1/gameCenterAchievements/a1/localizations?include=gameCenterAchievementImage&limit=200"

        /** A PNG's first bytes, enough for its size: the signature, then the header chunk with [side] by [side]. */
        fun png(side: Int): ByteArray =
            byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82) +
                listOf(side, side).flatMap { n -> (3 downTo 0).map { (n shr (8 * it)).toByte() } }

        /** The game in [dir] with its pictures, and App Store Connect knowing its app and nothing of Game Center. */
        fun game(dir: File): Pair<Achievements, FakeAppStore> {
            val achievements = Achievements.read(gameIn(dir), "en-US", dir)
            listOf("first", "blink").forEach {
                dir.resolve("$pictures/$it.png").apply { parentFile.mkdirs() }.writeBytes(png(512))
            }
            val api = FakeAppStore()
            api.documents[apps] = document("""{"type":"apps","id":"1","attributes":{"bundleId":"app.id"}}""")
            api.documents[detail] = null
            api.documents[list] = document()
            api.documents[localizations] = document()
            api.created["/v1/gameCenterDetails"] = single("""{"type":"gameCenterDetails","id":"gc"}""")
            api.created["/v1/gameCenterAchievements"] = single("""{"type":"gameCenterAchievements","id":"a1"}""")
            api.created["/v1/gameCenterAchievementLocalizations"] =
                single("""{"type":"gameCenterAchievementLocalizations","id":"l1"}""")
            api.created["/v1/gameCenterAchievementImages"] =
                single(
                    """{"type":"gameCenterAchievementImages","id":"i1","attributes":{"uploadOperations":[
                    {"method":"PUT","url":"https://upload/i1","offset":0,"length":24,"requestHeaders":[]}]}}""",
                )
            return achievements to api
        }

        fun sync(
            dir: File,
            achievements: Achievements,
            api: FakeAppStore,
            report: AppStoreReport,
        ) = GameCenterSync(api, achievements, dir.resolve(pictures), report).run("app.id")

        test("Game Center is switched on, and every achievement goes up with its texts and pictures") {
            val dir = root.resolve("new")
            val (achievements, api) = game(dir)
            val report = AppStoreReport("Game Center")
            sync(dir, achievements, api, report)
            api.writes.count { it == "POST /v1/gameCenterDetails" } shouldBe 1
            api.writes.count { it == "POST /v1/gameCenterAchievements" } shouldBe 2
            // The first in English, Italian and both Portuguese, the secret one in English alone
            api.writes.count { it == "POST /v1/gameCenterAchievementLocalizations" } shouldBe 5
            api.writes.count { it == "POST /v1/gameCenterAchievementImages" } shouldBe 5
            api.writes.count { it == "PATCH /v1/gameCenterAchievementImages/i1" } shouldBe 5
            val made =
                api.calls
                    .filter { it.first == "POST /v1/gameCenterAchievements" }
                    .map { checkNotNull(it.second)["data"]!!.jsonObject["attributes"]!!.jsonObject }
            made.map { it["vendorIdentifier"]!!.jsonPrimitive.content } shouldContainExactly listOf("first", "blink")
            made.map { it["showBeforeEarned"]!!.jsonPrimitive.content } shouldContainExactly listOf("true", "false")
            api.uploads.keys shouldBe setOf("https://upload/i1")
            report.markdown() shouldContain "Created 2 achievements"
            report.markdown() shouldContain "Add the new achievements to the version being prepared"
            report.failed shouldBe false
        }

        test("what Game Center already has, the same, stays as it is") {
            val dir = root.resolve("same")
            val (achievements, api) = game(dir)
            val picture = GameCenterSync.pictureName(dir.resolve("$pictures/first.png"))
            api.documents[detail] = single("""{"type":"gameCenterDetails","id":"gc"}""")
            api.documents[list] =
                document(
                    """{"type":"gameCenterAchievements","id":"a1","attributes":{"vendorIdentifier":"first",
                    "referenceName":"first","points":10,"showBeforeEarned":true,"repeatable":false}}""",
                )
            val text = { locale: String, name: String, note: String, image: String ->
                """{"type":"gameCenterAchievementLocalizations","id":"l$locale","attributes":{"locale":"$locale",
                "name":"$name","beforeEarnedDescription":"$note","afterEarnedDescription":"$note"},
                "relationships":{"gameCenterAchievementImage":{"data":{"type":"gameCenterAchievementImages",
                "id":"$image"}}}}"""
            }
            api.documents[localizations] =
                json(
                    """{"data":[${text("en-US", "Hello, world", "Play your first game", "e")},
                    ${text("it", "Ciao, mondo", "Gioca", "i")},${text("pt-BR", "Olá, mundo", "Jogue", "b")},
                    ${text("pt-PT", "Olá, mundo!", "Joga", "p")}],"included":[
                    ${listOf("e", "i", "b", "p").joinToString(",") {
                        """{"type":"gameCenterAchievementImages","id":"$it","attributes":{"fileName":"$picture"}}"""
                    }}]}""",
                )
            val report = AppStoreReport("Game Center")
            val first = Achievements(achievements.list.take(1), "en-US", emptyMap())
            GameCenterSync(api, first, dir.resolve(pictures), report).run("app.id")
            api.writes shouldBe emptyList()
            report.failed shouldBe false
        }

        test("App Store Connect's hourly limit stops the run, for the next one to go on") {
            val dir = root.resolve("limit")
            val (achievements, api) = game(dir)
            api.errors["POST /v1/gameCenterAchievements"] =
                "App Store Connect answered 429 to POST /v1/gameCenterAchievements"
            val report = AppStoreReport("Game Center")
            sync(dir, achievements, api, report)
            api.writes.count { it == "POST /v1/gameCenterAchievements" } shouldBe 1
            report.failed shouldBe false
            report.markdown() shouldContain "hourly limit"
        }

        test("a language App Store Connect refuses is left out, the others go on") {
            val dir = root.resolve("refused")
            val (achievements, api) = game(dir)
            api.errors["POST /v1/gameCenterAchievementLocalizations"] =
                "App Store Connect answered 409 to POST /v1/gameCenterAchievementLocalizations\n" +
                "ENTITY_ERROR.ATTRIBUTE.INVALID: An attribute value is invalid."
            val report = AppStoreReport("Game Center")
            sync(dir, achievements, api, report)
            api.writes.count { it == "POST /v1/gameCenterAchievements" } shouldBe 2
            report.failed shouldBe false
            report.markdown() shouldContain "refused the achievements' texts in en-US, left out: ENTITY_ERROR"
        }

        test("pictures of the wrong size or missing are named") {
            val dir = root.resolve("pictures")
            val (achievements, _) = game(dir)
            dir.resolve("$pictures/first.png").writeBytes(png(500))
            dir.resolve("$pictures/blink.png").delete()
            GameCenterSync.pictureProblems(achievements, dir.resolve(pictures)).joinToString("\n").let {
                it shouldContain "first.png is 500x500"
                it shouldContain "No picture for blink"
            }
        }
    })
