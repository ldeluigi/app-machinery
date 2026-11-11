package app.machinery.release

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration

/** A request the test server saw. */
private data class Seen(
    val method: String,
    val uri: String,
    val authorization: String?,
    val body: String,
)

class AppStoreConnectTest :
    FunSpec({
        val seen = mutableListOf<Seen>()
        val answers = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            seen +=
                Seen(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    body,
                )
            val queue = answers["${exchange.requestMethod} ${exchange.requestURI}"]
            val (status, text) = queue?.removeFirstOrNull() ?: (404 to "")
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        afterSpec { server.stop(0) }
        val host = "http://127.0.0.1:${server.address.port}"

        fun answer(
            call: String,
            vararg responses: Pair<Int, String>,
        ) {
            answers[call] = ArrayDeque(responses.toList())
        }

        val key =
            KeyPairGenerator
                .getInstance(
                    "EC",
                ).apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val api = AppStoreConnect("KEY", "ISSUER", key.private, host = host, retryAfter = Duration.ZERO)

        beforeEach {
            seen.clear()
            answers.clear()
        }

        test("collections are read through all their pages, with the filters' brackets escaped") {
            answer(
                "GET /v1/apps?filter%5BbundleId%5D=party.test",
                200 to """{"data":[{"type":"apps","id":"1"}],"links":{"next":"$host/v1/apps?cursor=2"}}""",
            )
            answer("GET /v1/apps?cursor=2", 200 to """{"data":[{"type":"apps","id":"2"}],"links":{}}""")
            api.getAll("/v1/apps?filter[bundleId]=party.test").map { it.id } shouldContainExactly listOf("1", "2")
            seen.first().authorization!! shouldStartWith "Bearer ey"
        }

        test("a resource that doesn't exist reads as null") {
            api.get("/v1/apps/9/appPriceSchedule") shouldBe null
        }

        test("changes go as JSON, and Apple's refusals are told") {
            answer(
                "PATCH /v1/apps/1",
                409 to """{"errors":[{"code":"ENTITY_ERROR","title":"Invalid","detail":"Not that value."}]}""",
            )
            val error =
                shouldThrow<IOException> {
                    api.patch("/v1/apps/1", AppStoreDocuments.update("apps", "1", mapOf("x" to JsonPrimitive("y"))))
                }
            error.message shouldBe
                "App Store Connect answered 409 to PATCH /v1/apps/1\nENTITY_ERROR: Invalid: Not that value."
            seen.single().body shouldBe """{"data":{"type":"apps","id":"1","attributes":{"x":"y"}}}"""
        }

        test("a read Apple fails for a moment is tried again; a write is not") {
            answer("GET /v1/territories", 503 to "", 200 to """{"data":[]}""")
            api.get("/v1/territories") shouldBe AppStoreDocuments.links("territories", emptyList())
            answer("POST /v1/betaGroups", 500 to "", 201 to """{"data":{"type":"betaGroups","id":"G"}}""")
            shouldThrow<IOException> {
                api.post(
                    "/v1/betaGroups",
                    AppStoreDocuments.create("betaGroups"),
                )
            }.message shouldContain
                "500 to POST /v1/betaGroups"
        }

        test("an upload operation sends its part with its own headers, and no token") {
            answer("PUT /upload/1", 200 to "")
            api.upload(
                UploadOperation("PUT", "$host/upload/1", 0, 3, mapOf("Content-Type" to "image/png")),
                byteArrayOf(1, 2, 3),
            )
            seen.single().authorization shouldBe null
            seen.single().body.length shouldBe 3
        }
    })
