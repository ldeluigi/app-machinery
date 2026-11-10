package app.machinery.release

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.io.path.createTempDirectory

class AppIdentityTest :
    FunSpec({
        val dir = createTempDirectory("app-identity").toFile()
        afterSpec { dir.deleteRecursively() }

        fun properties(content: String): File = dir.resolve("gradle.properties").apply { writeText(content) }

        test("reads the name and the id among the other properties") {
            val file =
                properties(
                    """
                    org.gradle.jvmargs=-Xmx4g
                    # The app's identity
                    app.name=My App
                    app.id=com.example.myapp
                    app.package=com.example.myapp
                    """.trimIndent(),
                )
            AppIdentity.of(file) shouldBe AppIdentity("My App", "com.example.myapp")
        }

        test("reads them as Gradle does: other separators, escapes, the last of two") {
            val file = properties("app.name : Caf\\u00e9 \\#1\napp.id=com.example.old\napp.id com.example.cafe\n")
            AppIdentity.of(file) shouldBe AppIdentity("Café #1", "com.example.cafe")
        }

        test("a missing or blank property fails, naming it") {
            val file = properties("app.name=\napp.id=com.example.myapp\n")
            shouldThrow<IllegalStateException> { AppIdentity.of(file) }.message shouldContain "app.name"
        }

        test("a missing file fails") {
            shouldThrow<IllegalStateException> { AppIdentity.of(dir.resolve("none.properties")) }
        }
    })
