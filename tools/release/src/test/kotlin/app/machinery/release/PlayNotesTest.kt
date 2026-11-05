package app.machinery.release

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

class PlayNotesTest :
    FunSpec({
        /** A changelog as semantic-release's conventionalcommits preset writes it. */
        val changelog =
            """
            ## [1.3.0](https://github.com/owner/repo/compare/v1.2.0...v1.3.0) (2026-10-14)

            ### Features

            * **app:** show the app version on the home screen ([4a34383](https://github.com/owner/repo/commit/4a34383))
            * add a cooperative mode ([#12](https://github.com/owner/repo/issues/12)) ([1234567](https://github.com/owner/repo/commit/1234567))

            ### Bug Fixes

            * **deps:** bump the gradle-minor-and-patch group with 3 updates ([89abcde](https://github.com/owner/repo/commit/89abcde))
            * **deps:** bump androidx.room from 2.8.5 to 2.8.6 ([fedcba9](https://github.com/owner/repo/commit/fedcba9))
            * **catalog:** monthly song catalogue refresh ([0011223](https://github.com/owner/repo/commit/0011223))
            """.trimIndent()

        test("a changelog's changes become plain sentences without links, hashes, scopes or pull requests") {
            val scopes = PlayNotes.MAINTENANCE_SCOPES + PlayNotes.scopes("catalog: Refreshed the song catalogue.")
            PlayNotes.changes(changelog, scopes) shouldBe
                listOf(
                    "Show the app version on the home screen",
                    "Add a cooperative mode",
                    "Updated components for stability and security.",
                    "Refreshed the song catalogue.",
                )
        }

        test("the issues a commit closed are left out") {
            val closing =
                """
                ### Features

                * **setup:** reorder players by dragging them ([89637e0](https://github.com/owner/repo/commit/89637e0)), closes [#19](https://github.com/owner/repo/issues/19)
                * **home:** show a constellation ([9e81469](https://github.com/owner/repo/commit/9e81469)), closes [#7](https://github.com/owner/repo/issues/7) [#8](https://github.com/owner/repo/issues/8)
                """.trimIndent()
            PlayNotes.changes(closing) shouldBe listOf("Reorder players by dragging them", "Show a constellation")
        }

        test("a changelog without changes gets a generic line") {
            PlayNotes.changes("## 1.0.1") shouldBe listOf(PlayNotes.NO_CHANGES)
        }

        test("the notes list each release's version as the app shows it, then its changes, newest first") {
            val releases =
                listOf(
                    ReleaseChanges("1.7.0", listOf("Replay the song from the beginning")),
                    ReleaseChanges("1.6.1", listOf("Fix the timer", "Updated components for stability and security.")),
                )
            PlayNotes.compose(releases) shouldBe
                """
                v1.7.0
                • Replay the song from the beginning
                v1.6.1
                • Fix the timer
                • Updated components for stability and security.
                """.trimIndent()
            PlayNotes.shown(releases) shouldBe releases
        }

        test("older releases that don't fit are left out, without an ellipsis") {
            val change = "A change with a description long enough to fill some room"
            val releases = (9 downTo 1).map { ReleaseChanges("1.$it.0", listOf(change)) }
            val notes = PlayNotes.compose(releases)
            notes.length shouldBeLessThanOrEqual PlayNotes.LIMIT
            notes.lines().last() shouldBe "• $change"
            PlayNotes.shown(releases) shouldBe releases.take(7)
        }

        test("a release cut short ends the notes with an ellipsis, never with its version alone") {
            val long = (1..40).joinToString("\n") { "* feature number $it with a fairly long description" }
            val releases =
                listOf(
                    ReleaseChanges("1.1.0", PlayNotes.changes(long)),
                    ReleaseChanges("1.0.1", listOf("Fix the timer")),
                )
            val notes = PlayNotes.compose(releases)
            notes.length shouldBeLessThanOrEqual PlayNotes.LIMIT
            notes shouldStartWith "v1.1.0\n• Feature number 1 with"
            notes shouldEndWith "\n…"
            PlayNotes.shown(releases) shouldBe releases.take(1)
            val tight =
                listOf(ReleaseChanges("1.1.0", listOf("x".repeat(PlayNotes.LIMIT - 20), "y".repeat(20))), releases[1])
            PlayNotes.compose(tight) shouldEndWith "x\n…"
        }

        test("a single change longer than the limit is cut within its line") {
            val notes = PlayNotes.compose(listOf(ReleaseChanges("1.0.1", listOf("x".repeat(PlayNotes.LIMIT)))))
            notes.length shouldBe PlayNotes.LIMIT
            notes shouldEndWith "x…"
        }

        test("versions sort newest first, 1.10.0 before 1.9.2") {
            listOf("1.9.2", "1.10.0", "2.0.0", "1.9.10").sortedWith(Versions.NEWEST_FIRST) shouldBe
                listOf("2.0.0", "1.10.0", "1.9.10", "1.9.2")
        }

        test("the release type follows from the version") {
            ReleaseType.of("2.0.0") shouldBe ReleaseType.MAJOR
            ReleaseType.of("1.4.0") shouldBe ReleaseType.MINOR
            ReleaseType.of("1.4.3") shouldBe ReleaseType.PATCH
        }

        test("a patch release with maintenance changes only is maintenance, even with the app's own scopes") {
            val scopes = PlayNotes.MAINTENANCE_SCOPES + PlayNotes.scopes("catalog: Refreshed the song catalogue.")
            val maintenance =
                """
                ## [1.3.1](https://github.com/o/r/compare/v1.3.0...v1.3.1) (2026-10-15)

                ### Bug Fixes

                * **deps:** bump androidx.room from 2.8.5 to 2.8.6 ([fedcba9](https://github.com/o/r/commit/fedcba9))
                * **catalog:** monthly song catalogue refresh ([0011223](https://github.com/o/r/commit/0011223))
                """.trimIndent()
            val fix = "$maintenance\n* **game:** the timer keeps running ([4a34383](https://github.com/o/r/c/4a34383))"
            val unscoped = "$maintenance\n* the timer keeps running ([4a34383](https://github.com/o/r/c/4a34383))"
            ReleaseType.of("1.3.1", maintenance, scopes) shouldBe ReleaseType.MAINTENANCE
            ReleaseType.of("1.3.1", maintenance) shouldBe ReleaseType.PATCH
            ReleaseType.of("1.3.1", fix, scopes) shouldBe ReleaseType.PATCH
            ReleaseType.of("1.3.1", unscoped, scopes) shouldBe ReleaseType.PATCH
            ReleaseType.of("1.3.1", "## 1.3.1", scopes) shouldBe ReleaseType.PATCH
            ReleaseType.of("1.4.0", maintenance, scopes) shouldBe ReleaseType.MINOR
        }

        test("major releases force an update, features and fixes ask, maintenance stays quiet") {
            ReleaseType.entries.associateWith { it.updatePriority } shouldBe
                mapOf(
                    ReleaseType.MAJOR to 5,
                    ReleaseType.MINOR to 3,
                    ReleaseType.PATCH to 2,
                    ReleaseType.MAINTENANCE to 1,
                )
        }
    })
