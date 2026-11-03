// Kotlin Multiplatform libraries shared by the JVM (the Android app and the tools) and iOS.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // KSP generates the test launcher of the native targets; Kotest's plugin wants it applied first.
    id("com.google.devtools.ksp")
    id("io.kotest")
    id("org.danilopianini.gradle-kotlin-qa")
}

kotlin {
    jvmToolchain(javaVersion)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies {
            implementation(lib("kotest-framework-engine"))
            implementation(lib("kotest-assertions-core"))
            implementation(lib("kotest-property"))
        }
        jvmTest.dependencies {
            implementation(lib("kotest-runner-junit5"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

iosSimulatorFromProperty()

checkHandWrittenSourcesOnly()

useProjectDetektConfig()
