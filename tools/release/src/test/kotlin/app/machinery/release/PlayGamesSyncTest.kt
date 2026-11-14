package app.machinery.release

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory

/** Google Play Games' achievements in memory, and what was made and changed. */
private class FakePlayGames(
    val items: MutableList<JsonObject> = mutableListOf(),
) : PlayGamesApi {
    val inserted = mutableListOf<JsonObject>()
    val updated = mutableListOf<Pair<String, JsonObject>>()

    override fun achievements(project: String): List<JsonObject> = items.toList()

    override fun insert(
        project: String,
        achievement: JsonObject,
    ): JsonObject {
        inserted += achievement
        return JsonObject(achievement + ("id" to JsonPrimitive("Cgk${items.size}"))).also(items::add)
    }

    override fun update(
        id: String,
        achievement: JsonObject,
    ) {
        updated += id to achievement
        items.replaceAll { if (it.string("id") == id) achievement else it }
    }
}

class PlayGamesSyncTest :
    FunSpec({
        val root = createTempDirectory("play-games").toFile()
        afterSpec { root.deleteRecursively() }

        val idsPath = "composeApp/src/androidMain/res/values/games-ids.xml"

        fun ids(
            dir: File,
            project: String,
        ): File =
            dir.write(
                idsPath,
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="game_services_project_id" translatable="false">$project</string>
                    <string name="achievement_first" translatable="false"></string>
                </resources>
                """.trimIndent() + "\n",
            )

        fun sync(
            dir: File,
            api: FakePlayGames,
            report: AppStoreReport = AppStoreReport(),
        ): String? {
            val achievements = Achievements.read(gameIn(dir), "en-US", dir)
            return PlayGamesSync(api, achievements, PlayGamesIds(dir.resolve(idsPath)), report).run()
        }

        test("the achievements Play lacks are made, and the ids Play gives them go into the app's file") {
            val dir = root.resolve("new")
            ids(dir, "123456789012")
            val api = FakePlayGames()
            val report = AppStoreReport()
            val written = checkNotNull(sync(dir, api, report))
            api.inserted.map { it.string("initialState") } shouldContainExactly listOf("REVEALED", "HIDDEN")
            val first = api.inserted.first()
            val draft = first["draft"] as JsonObject
            PlayGamesSync.translations(draft, "name") shouldBe
                mapOf(
                    "en-US" to "Hello, world",
                    "it-IT" to "Ciao, mondo",
                    "pt-BR" to "Olá, mundo",
                    "pt-PT" to "Olá, mundo!",
                )
            draft.string("pointValue") shouldBe "10"
            written shouldContain """<string name="achievement_first" translatable="false">Cgk0</string>"""
            written shouldContain """    <string name="achievement_blink" translatable="false">Cgk1</string>
</resources>"""
            report.markdown() shouldContain "Created 2 achievements"
            report.markdown() shouldContain "Publish the games services' changes"
            // Again: all there, nothing to do
            sync(dir, api) shouldBe null
            api.inserted.size shouldBe 2
            api.updated shouldBe emptyList()
        }

        test("an achievement whose id wasn't kept is found by its name, and what changed is written") {
            val dir = root.resolve("found")
            ids(dir, "123456789012")
            val achievements = Achievements.read(gameIn(dir), "en-US", dir)
            val (first, texts) = achievements.localized(PlayLanguages::codes).first()
            val old = PlayGamesSync.configuration(first.copy(points = 5), texts)
            val api = FakePlayGames(mutableListOf(JsonObject(old + ("id" to JsonPrimitive("CgkOld")))))
            sync(dir, api)
            api.inserted.size shouldBe 1
            api.updated.map { it.first } shouldContainExactly listOf("CgkOld")
            (api.updated.single().second["draft"] as JsonObject).string("pointValue") shouldBe "10"
            dir.resolve(idsPath).readText() shouldContain ">CgkOld</string>"
        }

        test("without the game's project id nothing is done, and the owner hears how to set it") {
            val dir = root.resolve("none")
            ids(dir, "")
            val api = FakePlayGames()
            val report = AppStoreReport()
            sync(dir, api, report) shouldBe null
            api.inserted shouldBe emptyList()
            report.markdown() shouldContain "No Play Games project id"
        }
    })
