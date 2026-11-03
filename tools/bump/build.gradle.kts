// The monthly refresh of the versions Dependabot cannot see (the JDK LTS, Android's SDK level) in plain version files.
// Run by the machinery refresh workflow; never shipped.
plugins {
    id("machinery.kotlin-jvm")
    application
}

application {
    mainClass = "app.machinery.bump.MainKt"
    applicationName = "bump"
}
