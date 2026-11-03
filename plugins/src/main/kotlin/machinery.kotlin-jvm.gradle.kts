// Pure Kotlin/JVM modules: game engine and command-line tools.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.danilopianini.gradle-kotlin-qa")
}

kotlin {
    jvmToolchain(javaVersion)
}

dependencies {
    "testImplementation"(lib("kotest-runner-junit5"))
    "testImplementation"(lib("kotest-assertions-core"))
    "testImplementation"(lib("kotest-property"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

useProjectDetektConfig()
