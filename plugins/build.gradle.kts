import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    `kotlin-dsl`
    `maven-publish`
    alias(libs.plugins.kotlin.qa)
}

group = "app.machinery"
version = providers.gradleProperty("machinery.version").getOrElse("0.0.0-SNAPSHOT")

// Bytecode for the oldest JDK that runs Android builds, whichever JVM compiles it: a build whose Gradle runs on a newer
// JVM (an IDE's bundled one) would otherwise leave class files that Gradle on an older JDK cannot load.
java {
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

tasks.withType<KotlinCompile>().configureEach {
    // Gradle's Kotlin DSL still passes this argument, deprecated by Kotlin 2.4.20.
    compilerOptions.freeCompilerArgs.add("-Xwarning-level=DEPRECATED_CLI_ARG:disabled")
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serialization.gradlePlugin)
    implementation(libs.compose.compiler.gradlePlugin)
    implementation(libs.kotlin.qa.gradlePlugin)
    implementation(libs.git.semver.gradlePlugin)
    implementation(libs.licensee.gradlePlugin)
    implementation(libs.aboutlibraries.gradlePlugin)
    implementation(libs.kotest.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.compose.multiplatform.gradlePlugin)
    implementation(libs.icu4j)
}

// The libraries and numbers the conventions give the apps' modules, written into the plugins' code from the version
// catalog: an app gets them by its machinery version alone.
val catalog = versionCatalogs.named("libs")
val libraries =
    listOf(
        "cmp-foundation",
        "cmp-ui",
        "cmp-material3",
        "cmp-resources",
        "cmp-ui-tooling-preview",
        "compose-bom",
        "kotlinx-serialization-json",
        "kotest-runner-junit5",
        "kotest-framework-engine",
        "kotest-assertions-core",
        "kotest-property",
        "junit4",
        "robolectric",
    ).associateWith { alias ->
        val library = catalog.findLibrary(alias).get().get()
        "${library.module}:${library.versionConstraint.requiredVersion}"
    }
val numbers =
    listOf("android-compileSdk", "android-targetSdk", "android-minSdk")
        .associateWith { catalog.findVersion(it).get().requiredVersion }

val generateCatalog by tasks.registering {
    description = "Writes the machinery's catalog into the plugins' code."
    val output = layout.buildDirectory.dir("generated/catalog")
    inputs.property("libraries", libraries)
    inputs.property("numbers", numbers)
    outputs.dir(output)
    doLast {
        fun entries(map: Map<String, String>) = map.entries.joinToString("\n") { (k, v) -> "        \"$k\" to \"$v\"," }
        output.get().file("MachineryCatalog.kt").asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |/** The machinery's version catalog, as this release of the plugins carries it. */
            |internal object MachineryCatalog {
            |    val libraries: Map<String, String> = mapOf(
            |${entries(libraries)}
            |    )
            |
            |    val versions: Map<String, String> = mapOf(
            |${entries(numbers)}
            |    )
            |}
            |
            """.trimMargin(),
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generateCatalog)
}

publishing {
    repositories {
        // A Maven repository in a folder, which the release workflow publishes on the machinery's GitHub Pages
        maven {
            name = "site"
            url =
                uri(
                    providers
                        .gradleProperty(
                            "machinery.repository",
                        ).getOrElse(
                            layout.buildDirectory
                                .dir("repository")
                                .get()
                                .asFile.path,
                        ),
                )
        }
    }
}

// The plugins pass the checks they give the apps (gradle-kotlin-qa, with the machinery's detekt settings on top of its
// defaults), on their hand-written code only: Gradle's accessors for the precompiled scripts and the catalog above are
// generated into build/.
val generated = layout.buildDirectory.get().asFile
afterEvaluate {
    tasks.withType<Detekt>().configureEach {
        config.setFrom(layout.buildDirectory.file("kotlinqa/detekt.yml"), "src/main/resources/machinery/detekt.yml")
    }
}
tasks.withType<Detekt>().configureEach { exclude { it.file.startsWith(generated) } }
configure<KtlintExtension> { filter { exclude { it.file.startsWith(generated) } } }
tasks.withType<SourceTask>().matching { it.name == "cpdKotlinCheck" }.configureEach {
    setSource(fileTree("src") { include("**/*.kt") })
}
