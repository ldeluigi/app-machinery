package app.machinery.thread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** GitHub's API for [repository] (`owner/name`): REST at [api], GraphQL at [graphql], with [token]. */
class GitHub(
    private val api: String,
    private val graphql: String,
    private val repository: String,
    private val token: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()

    /** Issue or pull request [number], with all that [ReadableThread] needs of it. */
    fun conversation(number: Int): Conversation {
        val owner = repository.substringBefore('/')
        val name = repository.substringAfter('/')
        val query =
            buildJsonObject {
                put("query", QUERY)
                putJsonObject("variables") {
                    put("owner", owner)
                    put("name", name)
                    put("number", number)
                }
            }
        val request = request(graphql).POST(HttpRequest.BodyPublishers.ofString(query.toString())).build()
        return parseConversation(send(request).body(), number)
    }

    /** Whether [login] has write access to the repository (the write, maintain or admin role). */
    fun hasWriteAccess(login: String): Boolean {
        val user = URLEncoder.encode(login, Charsets.UTF_8)
        val response = send(request("$api/repos/$repository/collaborators/$user/permission").GET().build())
        // GitHub answers "not a user" for an account it doesn't know
        return response.statusCode() != NOT_FOUND && parsePermission(response.body()) in WRITE_ACCESS
    }

    private fun request(url: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "app-machinery")
            .timeout(Duration.ofSeconds(TIMEOUT_S))

    private fun send(request: HttpRequest): HttpResponse<String> {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in OK && response.statusCode() != NOT_FOUND) {
            throw IOException(
                "GitHub answered ${response.statusCode()} to ${request.uri()}: ${response.body().take(ERROR_CHARS)}",
            )
        }
        return response
    }

    /** Reading GitHub's answers. */
    companion object {
        private const val TIMEOUT_S = 60L
        private const val NOT_FOUND = 404
        private const val ERROR_CHARS = 500
        private const val HUNK_LINES = 8
        private val OK = 200..299

        /** `permission` maps the maintain role to write, and a custom role to the one it builds on. */
        private val WRITE_ACCESS = setOf("admin", "maintain", "write")
        private val VERDICTS =
            mapOf("APPROVED" to "approved", "CHANGES_REQUESTED" to "changes requested", "DISMISSED" to "dismissed")

        /** The latest 100 comments, timeline events and (of 50 reviews) review comments are plenty for a task. */
        private val QUERY =
            """
            query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                issueOrPullRequest(number: ${'$'}number) {
                  __typename
                  ... on Issue {
                    title body createdAt lastEditedAt author { ...who } editor { ...who }
                    comments(last: 100) { totalCount nodes { ...comment } }
                    timelineItems(last: 100, itemTypes: [LABELED_EVENT, RENAMED_TITLE_EVENT]) { nodes { ...event } }
                  }
                  ... on PullRequest {
                    title body createdAt lastEditedAt author { ...who } editor { ...who }
                    headRefName baseRefName isCrossRepository
                    comments(last: 100) { totalCount nodes { ...comment } }
                    timelineItems(last: 100, itemTypes: [LABELED_EVENT, RENAMED_TITLE_EVENT]) { nodes { ...event } }
                    reviews(last: 50) {
                      nodes {
                        body state createdAt lastEditedAt author { ...who } editor { ...who }
                        comments(first: 50) {
                          nodes {
                            body path line originalLine diffHunk createdAt lastEditedAt isMinimized
                            author { ...who } editor { ...who }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            fragment who on Actor { __typename login }
            fragment comment on IssueComment {
              body createdAt lastEditedAt isMinimized author { ...who } editor { ...who }
            }
            fragment event on Node {
              __typename
              ... on LabeledEvent { createdAt actor { ...who } label { name } }
              ... on RenamedTitleEvent { createdAt actor { ...who } previousTitle currentTitle }
            }
            """.trimIndent()

        /** The [Conversation] of issue or pull request [number] in GitHub's answer [json] to the query. */
        fun parseConversation(
            json: String,
            number: Int,
        ): Conversation {
            val root = Json.parseToJsonElement(json).jsonObject
            root["errors"]?.let { throw IOException("GitHub refused the query: $it") }
            val item =
                root.obj("data").obj("repository")["issueOrPullRequest"] as? JsonObject
                    ?: throw IOException("#$number is neither an issue nor a pull request of the repository")
            val comments = item.obj("comments")
            val commentNodes = comments.nodes()
            val events = item.obj("timelineItems").nodes()
            val posts = commentNodes.map { post(PostKind.COMMENT, it) } + item.obj("reviews").nodes().flatMap(::review)
            return Conversation(
                number = number,
                title = item.text("title"),
                posts = listOf(post(PostKind.OPENING, item)) + posts.sortedBy { it.createdAt },
                labelings =
                    events.filter { it.text("__typename") == "LabeledEvent" }.map {
                        Labeling(it.author("actor"), it.instant("createdAt"), it.obj("label").text("name"))
                    },
                renamings =
                    events.filter { it.text("__typename") == "RenamedTitleEvent" }.map {
                        Renaming(
                            it.author("actor"),
                            it.instant("createdAt"),
                            it.text("previousTitle"),
                            it.text("currentTitle"),
                        )
                    },
                branches =
                    if (item.text("__typename") == "PullRequest") {
                        Branches(item.text("headRefName"), item.text("baseRefName"), item.flag("isCrossRepository"))
                    } else {
                        null
                    },
                olderComments = ((comments.int("totalCount") ?: 0) - commentNodes.size).coerceAtLeast(0),
            )
        }

        /** The role in GitHub's answer [json] about a collaborator's permission. */
        fun parsePermission(json: String): String = Json.parseToJsonElement(json).jsonObject.text("permission")

        private fun post(
            kind: PostKind,
            node: JsonObject,
        ) = Post(
            kind = kind,
            author = node.author("author"),
            createdAt = node.instant("createdAt"),
            body = node.text("body"),
            lastEditedAt = node.text("lastEditedAt").takeIf { it.isNotEmpty() }?.let(Instant::parse),
            editor = node.author("editor"),
            minimized = node.flag("isMinimized"),
        )

        /** A review's verdict, unless it only holds comments, and its comments on the code. */
        private fun review(node: JsonObject): List<Post> {
            val state = node.text("state")
            if (state == "PENDING") return emptyList()
            val verdict = post(PostKind.REVIEW, node).copy(about = VERDICTS[state].orEmpty())
            val ownText = node.text("body").isNotBlank() || state != "COMMENTED"
            return listOfNotNull(verdict.takeIf { ownText }) +
                node.obj("comments").nodes().map { comment ->
                    val line = comment.int("line") ?: comment.int("originalLine")
                    post(PostKind.REVIEW_COMMENT, comment).copy(
                        about = "`${comment.text("path")}`" + line?.let { " line $it" }.orEmpty(),
                        code =
                            comment
                                .text("diffHunk")
                                .lines()
                                .takeLast(HUNK_LINES)
                                .joinToString("\n"),
                    )
                }
        }

        private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())

        private fun JsonObject.nodes(): List<JsonObject> =
            (this["nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

        private fun JsonObject.instant(name: String): Instant = Instant.parse(text(name))

        /** GraphQL gives an app's login without the `[bot]` that REST, the events and the logins of apps have. */
        private fun JsonObject.author(name: String): Author? =
            (this[name] as? JsonObject)?.let {
                val login = it.text("login")
                val bot = it.text("__typename") == "Bot"
                Author(if (bot && !login.endsWith("[bot]")) "$login[bot]" else login, bot)
            }
    }
}
