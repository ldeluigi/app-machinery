import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Adds kotlinx.serialization code generation to a Kotlin module, with its JSON format.
plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

val json = lib("kotlinx-serialization-json")
if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
    extensions.configure<KotlinMultiplatformExtension> { sourceSets.commonMain.dependencies { implementation(json) } }
} else {
    dependencies { "implementation"(json) }
}
