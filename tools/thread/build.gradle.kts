// What an AI agent may read of an issue or pull request (bin/thread): what the repository's maintainers wrote, what
// others wrote before a maintainer called the agent, and none of the text the page doesn't show. The automation's agent
// jobs run it from installDist (.github/actions/thread); never shipped.
plugins {
    id("machinery.kotlin-jvm")
    application
}

application {
    mainClass = "app.machinery.thread.MainKt"
    applicationName = "thread"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.footnotes)
}
