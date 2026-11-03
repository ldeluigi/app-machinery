package app.machinery.release

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GitHubReleasesTest :
    FunSpec({
        test("published releases come newest version first, without drafts or pre-releases") {
            val json =
                """
                [
                  {"tag_name":"v1.9.2","body":"## 1.9.2\n* fix","draft":false,"prerelease":false},
                  {"tag_name":"v1.11.0","body":null,"draft":true,"prerelease":false},
                  {"tag_name":"v1.10.0","body":"## 1.10.0\n* feat","draft":false,"prerelease":false},
                  {"tag_name":"v2.0.0-rc.1","body":"","draft":false,"prerelease":true},
                  {"tag_name":"v1.9.1","draft":false,"prerelease":false}
                ]
                """.trimIndent()
            GitHubReleases.parse(json) shouldBe
                listOf(
                    PublishedRelease("1.10.0", "## 1.10.0\n* feat"),
                    PublishedRelease("1.9.2", "## 1.9.2\n* fix"),
                    PublishedRelease("1.9.1", ""),
                )
        }
    })
