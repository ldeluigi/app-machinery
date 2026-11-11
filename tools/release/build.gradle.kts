// The stores' "What's new" and in-app update priority for the release semantic-release just made (bin/release), the
// Google Play listing's graphics (bin/upload-listing) and the App Store listing (bin/app-store). The release workflows
// run them from installDist; never shipped.
plugins {
    id("machinery.kotlin-jvm")
    application
}

application {
    mainClass = "app.machinery.release.MainKt"
    applicationName = "release"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

val uploadListingScripts by tasks.registering(CreateStartScripts::class) {
    description = "Writes the start scripts of upload-listing, which replaces the Play listing's graphics that changed."
    mainClass = "app.machinery.release.UploadListingKt"
    applicationName = "upload-listing"
    outputDir =
        layout.buildDirectory
            .dir("upload-listing-scripts")
            .map { it.asFile }
            .get()
    classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
}

val appStoreScripts by tasks.registering(CreateStartScripts::class) {
    description = "Writes the start scripts of app-store, which fills in the app's App Store Connect record."
    mainClass = "app.machinery.release.AppStoreKt"
    applicationName = "app-store"
    outputDir =
        layout.buildDirectory
            .dir("app-store-scripts")
            .map { it.asFile }
            .get()
    classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
}

distributions.main {
    contents {
        from(uploadListingScripts) { into("bin") }
        from(appStoreScripts) { into("bin") }
    }
}
