package app.machinery.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.awt.image.BufferedImage
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory

class AppStoreListingTest :
    FunSpec({
        val dir = createTempDirectory("listing").toFile()
        afterSpec { dir.deleteRecursively() }

        test("the texts are read by language, trimmed, with the app's name") {
            val listing = AppStoreListing.read(listingIn(dir.resolve("good")), "Example")
            listing.texts.keys.toList() shouldContainExactly listOf("de-DE", "en-US")
            listing.texts.getValue("en-US")[ListingText.NAME] shouldBe "Example"
            listing.texts.getValue("en-US")[ListingText.DESCRIPTION] shouldBe "Example is a party game.\n\nPlay it."
            listing.texts.getValue("de-DE") shouldContainExactly
                mapOf(ListingText.NAME to "Example", ListingText.DESCRIPTION to "Example ist ein Partyspiel.")
            listing.reviewNotes shouldBe "No account needed."
        }

        test("the settings give single values, lists and groups") {
            val listing = AppStoreListing.read(listingIn(dir.resolve("settings")), "Example")
            listing.setting("primaryCategory") shouldBe "GAMES"
            listing.setting("marketingUrl") shouldBe null
            listing.list("testflight.internalTesterRoles") shouldContainExactly listOf("ACCOUNT_HOLDER", "ADMIN")
            listing.group("ageRating.") shouldContainExactly
                mapOf("profanityOrCrudeHumor" to "INFREQUENT", "unrestrictedWebAccess" to "false")
        }

        test("texts over Apple's limits and missing ones stop the upload, all of them listed") {
            val bad = listingIn(dir.resolve("bad"))
            bad.resolve("en-US/subtitle.txt").writeText("A subtitle much longer than thirty characters")
            bad.resolve("de-DE/description.txt").delete()
            bad.resolve("fr-FR").mkdirs()
            bad.resolve("fr-FR/keywords.txt").writeText("x".repeat(101))
            val message = shouldThrow<IllegalStateException> { AppStoreListing.read(bad, "Example") }.message
            message shouldContain "en-US/subtitle.txt has 45 characters, Apple takes 30"
            message shouldContain "de-DE/description.txt is missing"
            message shouldContain "fr-FR/name.txt is missing"
            message shouldContain "fr-FR/keywords.txt has 101 characters, Apple takes 100"
        }

        test("characters are counted as Apple counts them, not as UTF-16 units") {
            val emoji = listingIn(dir.resolve("emoji"))
            emoji.resolve("en-US/subtitle.txt").writeText("🎵".repeat(30))
            AppStoreListing
                .read(emoji, "Example")
                .texts
                .getValue("en-US")[ListingText.SUBTITLE]!!
                .length shouldBe 60
        }

        test("attribute values: true and false are booleans, the rest text") {
            AppStoreDocuments.value("false") shouldBe JsonPrimitive(false)
            AppStoreDocuments.value("NONE") shouldBe JsonPrimitive("NONE")
            val current = Json.parseToJsonElement("""{"a":"NONE","b":false,"c":null}""").jsonObject
            val wanted = mapOf("a" to JsonPrimitive("NONE"), "b" to JsonPrimitive(false), "c" to JsonPrimitive("X"))
            AppStoreDocuments.changes(current, wanted) shouldBe mapOf("c" to JsonPrimitive("X"))
        }

        test("the API's tokens are ES256 JSON Web Tokens of the team's key") {
            val keys =
                KeyPairGenerator
                    .getInstance(
                        "EC",
                    ).apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            val pem =
                "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder().encodeToString(keys.private.encoded) +
                    "\n-----END PRIVATE KEY-----\n"
            val now = Instant.parse("2026-11-11T10:00:00Z")
            val token = AppStoreToken.sign("KEY123", "issuer-1", AppStoreToken.key(pem), now)
            val (header, payload, signature) = token.split(".")

            fun decode(part: String) = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part))).jsonObject
            decode(header).mapValues { it.value.jsonPrimitive.content } shouldBe
                mapOf("alg" to "ES256", "kid" to "KEY123", "typ" to "JWT")
            val claims = decode(payload)
            claims.getValue("aud").jsonPrimitive.content shouldBe "appstoreconnect-v1"
            claims.getValue("iss").jsonPrimitive.content shouldBe "issuer-1"
            claims.getValue("exp").jsonPrimitive.long - claims.getValue("iat").jsonPrimitive.long shouldBe 19 * 60L
            Signature
                .getInstance("SHA256withECDSAinP1363Format")
                .apply {
                    initVerify(keys.public)
                    update("$header.$payload".toByteArray())
                }.verify(Base64.getUrlDecoder().decode(signature)) shouldBe true
            // A secret pasted on one line, with its line breaks written as \n, reads the same
            AppStoreToken.key(pem.replace("\n", "\\n")).encoded.toList() shouldBe keys.private.encoded.toList()
        }

        test("Apple's errors are told by code and detail") {
            val body =
                """{"errors":[{"status":"409","code":"ENTITY_ERROR.ATTRIBUTE.INVALID","title":"Bad","detail":"No."}]}"""
            AppStoreConnect.failure("PATCH", "/v1/apps/1", 409, body) shouldBe
                "App Store Connect answered 409 to PATCH /v1/apps/1\nENTITY_ERROR.ATTRIBUTE.INVALID: Bad: No."
        }

        test("upload operations are read with their headers") {
            val operations =
                UploadOperation.of(
                    Json.parseToJsonElement(
                        """[{"method":"PUT","url":"https://u/1","offset":0,"length":10,
                        "requestHeaders":[{"name":"Content-Type","value":"image/png"}]}]""",
                    ),
                )
            operations shouldContainExactly
                listOf(UploadOperation("PUT", "https://u/1", 0, 10, mapOf("Content-Type" to "image/png")))
        }

        test("screenshots are found by display type, in the order of their names, at sizes Apple takes") {
            val art = dir.resolve("art")

            fun png(
                path: String,
                width: Int,
                height: Int,
            ) = art.resolve(path).apply {
                parentFile.mkdirs()
                ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", this)
            }
            val second = png("APP_IPHONE_67/2-play.png", 1320, 2868)
            val first = png("APP_IPHONE_67/1-home.png", 1320, 2868)
            val ipad = png("APP_IPAD_PRO_3GEN_129/1-home.png", 2064, 2752)
            ScreenshotSync.from(art) shouldBe
                mapOf("APP_IPAD_PRO_3GEN_129" to listOf(ipad), "APP_IPHONE_67" to listOf(first, second))
            png("APP_IPHONE_67/3-wrong.png", 1080, 1920)
            shouldThrow<IllegalStateException> { ScreenshotSync.from(art) }.message shouldContain
                "APP_IPHONE_67/3-wrong.png is 1080x1920, Apple takes 1320x2868, 1290x2796, 1260x2736"
        }
    })
