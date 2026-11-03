package app.machinery.bump

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File

class MachineryTest :
    FunSpec({
        /** A repository with the version files the machinery reads. */
        fun repository(targetSdk: String = "37"): File =
            tempdir().also { root ->
                File(root, ".java-version").writeText("21\n")
                File(root, "gradle").mkdirs()
                File(root, "gradle/libs.versions.toml").writeText(
                    "[versions]\nandroid-compileSdk = \"37\"\n" +
                        "android-targetSdk = \"$targetSdk\"\nandroid-minSdk = \"26\"\n",
                )
            }

        test("versions only move forward") {
            Machinery.isNewer("25", "21") shouldBe true
            Machinery.isNewer("21", "25") shouldBe false
            Machinery.isNewer("v0.37.0", "v0.36.9") shouldBe true
            Machinery.isNewer("v0.36.0", "v0.36.0") shouldBe false
        }

        test("upstream indexes are parsed") {
            val repositoryXml =
                """<p path="platforms;android-36"/><p path="platforms;android-37.2"/>""" +
                    """<p path="platforms;android-38-beta1"/>"""
            Machinery.latestAndroidApi(repositoryXml) shouldBe 37
            Machinery.latestJdkLts("""{"available_lts_releases":[17,21,25],"most_recent_lts": 25}""") shouldBe 25
        }

        test("version files and the catalog are bumped in place") {
            val root = repository(targetSdk = "36")
            val bumps = Machinery(root).apply(Latest(jdkLts = 21, androidApi = 38))
            bumps.filter { it.changed }.map { it.name } shouldBe listOf("compileSdk", "targetSdk")
            File(root, "gradle/libs.versions.toml").readText() shouldBe
                "[versions]\nandroid-compileSdk = \"38\"\nandroid-targetSdk = \"38\"\nandroid-minSdk = \"26\"\n"
        }

        test("unknown latest versions leave files untouched") {
            Machinery(repository()).apply(Latest(null, null)).none { it.changed } shouldBe true
        }

        test("an app without the SDK levels in its catalog only gets its JDK bumped") {
            val root = tempdir().also { File(it, ".java-version").writeText("21\n") }
            Machinery(root).apply(Latest(jdkLts = 25, androidApi = 38)).map { it.name } shouldBe listOf("JDK LTS")
            File(root, ".java-version").readText() shouldBe "25\n"
        }
    })
