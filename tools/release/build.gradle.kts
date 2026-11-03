// The stores' "What's new" and in-app update priority for the release semantic-release just made (bin/release), and
// the store listing's graphics (bin/upload-listing). The release workflows run them from installDist; never shipped.
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
    outputDir = layout.buildDirectory.dir("upload-listing-scripts").get().asFile
    classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
}

distributions.main {
    contents {
        from(uploadListingScripts) { into("bin") }
    }
}
