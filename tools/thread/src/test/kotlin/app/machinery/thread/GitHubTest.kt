package app.machinery.thread

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.time.Instant

class GitHubTest :
    FunSpec({
        test("an issue: its description, comments, labels and renames, with apps' logins as REST gives them") {
            val json =
                """
                {"data":{"repository":{"issueOrPullRequest":{
                  "__typename":"Issue","title":"Crash on start","body":"It crashes.",
                  "createdAt":"2026-11-01T10:00:00Z","lastEditedAt":"2026-11-01T11:00:00Z",
                  "author":{"__typename":"User","login":"stranger"},"editor":{"__typename":"User","login":"stranger"},
                  "comments":{"totalCount":102,"nodes":[
                    {"body":"Answer.","createdAt":"2026-11-01T13:00:00Z","lastEditedAt":null,"isMinimized":false,
                     "author":{"__typename":"Bot","login":"machinery-bot"},"editor":null},
                    {"body":"Spam.","createdAt":"2026-11-01T12:00:00Z","lastEditedAt":null,"isMinimized":true,
                     "author":null,"editor":null}]},
                  "timelineItems":{"nodes":[
                    {"__typename":"LabeledEvent","createdAt":"2026-11-01T12:30:00Z",
                     "actor":{"__typename":"User","login":"owner"},"label":{"name":"claude"}},
                    {"__typename":"RenamedTitleEvent","createdAt":"2026-11-01T10:30:00Z",
                     "actor":{"__typename":"User","login":"stranger"},"previousTitle":"Crash","currentTitle":"Crash on start"}]}
                }}}}
                """.trimIndent()
            val conversation = GitHub.parseConversation(json, 5)
            conversation.title shouldBe "Crash on start"
            conversation.branches shouldBe null
            conversation.olderComments shouldBe 100
            conversation.posts.map { it.kind } shouldBe listOf(PostKind.OPENING, PostKind.COMMENT, PostKind.COMMENT)
            conversation.posts[0] shouldBe
                Post(
                    PostKind.OPENING,
                    Author("stranger"),
                    Instant.parse("2026-11-01T10:00:00Z"),
                    "It crashes.",
                    Instant.parse("2026-11-01T11:00:00Z"),
                    Author("stranger"),
                )
            conversation.posts[1].author shouldBe null
            conversation.posts[1].minimized shouldBe true
            conversation.posts[2].author shouldBe Author("machinery-bot[bot]", isBot = true)
            conversation.labelings shouldBe
                listOf(Labeling(Author("owner"), Instant.parse("2026-11-01T12:30:00Z"), "claude"))
            conversation.renamings shouldBe
                listOf(Renaming(Author("stranger"), Instant.parse("2026-11-01T10:30:00Z"), "Crash", "Crash on start"))
        }

        test("a pull request: its branches, reviews with a verdict or their own text, and comments on the code") {
            val json =
                """
                {"data":{"repository":{"issueOrPullRequest":{
                  "__typename":"PullRequest","title":"Faster start","body":"","createdAt":"2026-11-01T10:00:00Z",
                  "lastEditedAt":null,"author":{"__typename":"User","login":"owner"},"editor":null,
                  "headRefName":"agent/issue-5","baseRefName":"main","isCrossRepository":false,
                  "comments":{"totalCount":0,"nodes":[]},"timelineItems":{"nodes":[]},
                  "reviews":{"nodes":[
                    {"body":"","state":"COMMENTED","createdAt":"2026-11-01T11:00:00Z","lastEditedAt":null,
                     "author":{"__typename":"User","login":"owner"},"editor":null,
                     "comments":{"nodes":[{"body":"Rename.","path":"App.kt","line":null,"originalLine":3,
                       "diffHunk":"@@ -1,2 +1,3 @@\n a\n+b","createdAt":"2026-11-01T11:00:00Z","lastEditedAt":null,
                       "isMinimized":false,"author":{"__typename":"User","login":"owner"},"editor":null}]}},
                    {"body":"","state":"APPROVED","createdAt":"2026-11-01T12:00:00Z","lastEditedAt":null,
                     "author":{"__typename":"User","login":"owner"},"editor":null,"comments":{"nodes":[]}},
                    {"body":"Draft.","state":"PENDING","createdAt":"2026-11-01T12:30:00Z","lastEditedAt":null,
                     "author":{"__typename":"User","login":"owner"},"editor":null,"comments":{"nodes":[]}}]}
                }}}}
                """.trimIndent()
            val conversation = GitHub.parseConversation(json, 6)
            conversation.branches shouldBe Branches("agent/issue-5", "main")
            conversation.posts.map { it.kind to it.about } shouldBe
                listOf(
                    PostKind.OPENING to "",
                    PostKind.REVIEW_COMMENT to "`App.kt` line 3",
                    PostKind.REVIEW to "approved",
                )
            conversation.posts[1].code shouldBe "@@ -1,2 +1,3 @@\n a\n+b"
        }

        test("an error, or no such issue, fails rather than reading less") {
            shouldThrow<IOException> { GitHub.parseConversation("""{"errors":[{"message":"Bad credentials"}]}""", 1) }
            shouldThrow<IOException> {
                GitHub.parseConversation("""{"data":{"repository":{"issueOrPullRequest":null}}}""", 1)
            }
        }

        test("a collaborator's permission") {
            GitHub.parsePermission("""{"permission":"write","role_name":"maintain"}""") shouldBe "write"
            GitHub.parsePermission("""{"permission":"none","role_name":""}""") shouldBe "none"
        }
    })
