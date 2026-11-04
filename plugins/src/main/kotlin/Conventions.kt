import app.cash.licensee.LicenseeExtension
import dev.detekt.gradle.Detekt
import org.gradle.api.Project
import org.gradle.api.tasks.SourceTask
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * A library the conventions add to the app's modules, as `group:name:version` from the machinery's own version
 * catalog (gradle/libs.versions.toml of app-machinery), so that apps get them with the machinery's version.
 */
internal fun lib(alias: String): String = MachineryCatalog.libraries.getValue(alias)

/** A number from the machinery's version catalog, like an Android SDK level. */
internal fun int(alias: String): Int = MachineryCatalog.versions.getValue(alias).toInt()

/** JDK used for toolchains, read from the repository-wide `.java-version` file (kept current by CI). */
internal val Project.javaVersion: Int
    get() =
        rootProject.layout.projectDirectory
            .file(".java-version")
            .asFile
            .readText()
            .trim()
            .toInt()

/**
 * Adds the machinery's detekt settings on top of the gradle-kotlin-qa defaults, and the app's own
 * (`config/detekt/detekt.yml`, if it has one) on top of those: detekt merges configuration files key by key, later
 * ones winning.
 */
internal fun Project.useProjectDetektConfig() {
    val defaults = layout.buildDirectory.file("kotlinqa/detekt.yml")
    val machinery = layout.buildDirectory.file("machinery/detekt.yml")
    val app = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")
    val write =
        tasks.register("machineryDetektConfig") {
            description = "Writes the machinery's detekt settings where detekt reads them."
            val output = machinery
            outputs.file(output)
            doLast {
                val text = checkNotNull(MachineryCatalog::class.java.getResource("/machinery/detekt.yml")).readText()
                output
                    .get()
                    .asFile
                    .apply { parentFile.mkdirs() }
                    .writeText(text)
            }
        }
    afterEvaluate {
        tasks.withType(Detekt::class.java).configureEach {
            dependsOn(write)
            config.setFrom(listOfNotNull(defaults, machinery, app.takeIf { file -> file.asFile.exists() }))
        }
    }
}

/**
 * The licences every shipped dependency must carry, checked by Licensee on the modules that ship: the open-source
 * ones, plus the one exception of the stores' own libraries (Google Play in-app updates and the Play services runtime
 * they need, as Play policy requires apps on Google Play to update through Play itself).
 */
internal fun Project.allowOpenSourceLicences() {
    extensions.configure<LicenseeExtension> {
        allow("Apache-2.0")
        allow("MIT")
        allow("BSD-2-Clause")
        allow("BSD-3-Clause")
        allow("CC0-1.0")
        allowUrl("https://developer.android.com/guide/playcore/license") { because("Google Play in-app updates") }
        allowUrl("https://developer.android.com/studio/terms.html") {
            because("Play services runtime of in-app updates")
        }
    }
}

/**
 * Points gradle-kotlin-qa's checks at the hand-written sources. Kotlin's source sets also hold generated code in
 * multiplatform modules (Compose resource accessors, test launchers), which detekt and ktlint would otherwise check,
 * and hold nothing at all in Android modules built with the Android Gradle plugin's own Kotlin, where the copy-paste
 * check (cpdKotlinCheck) would otherwise check nothing.
 */
internal fun Project.checkHandWrittenSourcesOnly() {
    tasks.withType<SourceTask>().matching { it.name == "cpdKotlinCheck" }.configureEach {
        setSource(fileTree("src") { include("**/*.kt") })
    }
    val generated = layout.buildDirectory.get().asFile
    tasks.withType<Detekt>().configureEach { exclude { it.file.startsWith(generated) } }
    extensions.configure<KtlintExtension> { filter { exclude { it.file.startsWith(generated) } } }
}

/** The simulator the iOS tests run on (macOS only): `-Pios.simulator=<device>`, as CI passes one of its runner's. */
internal fun Project.iosSimulatorFromProperty() {
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        device.set(providers.gradleProperty("ios.simulator").orElse("iPhone 17"))
    }
}

/**
 * The app's version: the one semantic-release is about to tag (`-PreleaseVersion`, see .releaserc.yml), otherwise
 * the one git tags give (git-sensitive-semantic-versioning, applied by the conventions that need it).
 */
internal val Project.appVersion: AppVersion
    get() = AppVersion.parse(providers.gradleProperty("releaseVersion").orNull ?: version.toString())
