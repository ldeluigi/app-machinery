// The shared Compose Multiplatform app as a library: Android, consumed by the Android entry point, and iOS.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.android.lint")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.danilopianini.gradle-kotlin-qa")
    id("app.cash.licensee")
    id("com.mikepenz.aboutlibraries.plugin")
    id("org.danilopianini.git-sensitive-semantic-versioning")
}

gitSemVer {
    versionPrefix = "v"
}

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    android {
        namespace = "${app.codePackage}.shared"
        compileSdk = int("android-compileSdk")
        minSdk = int("android-minSdk")
        // Android resources live in src/androidMain/res, and the Compose resources ride on Android assets.
        androidResources { enable = true }
        withHostTest { isIncludeAndroidResources = true }
    }
    // The iOS app links the shared code as one static framework (iosApp/project.yml).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // iOS code calls Apple's C and Objective-C APIs throughout.
        target.compilations.configureEach {
            compileTaskProvider.configure { compilerOptions.optIn.add("kotlinx.cinterop.ExperimentalForeignApi") }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(lib("cmp-foundation"))
            api(lib("cmp-material3"))
            api(lib("cmp-ui"))
            implementation(lib("cmp-resources"))
            implementation(lib("cmp-ui-tooling-preview"))
        }
        // Host tests run with JUnit 4 and Robolectric; the source set exists once withHostTest has run.
        matching { it.name == "androidHostTest" }.configureEach {
            dependencies {
                implementation(lib("junit4"))
                implementation(lib("robolectric"))
            }
        }
    }
}

iosSimulatorFromProperty()

// Host tests draw with Robolectric's native graphics, as a phone does (its legacy mode can't make every bitmap).
tasks.withType<Test>().configureEach { systemProperty("robolectric.graphicsMode", "NATIVE") }

// The app's identity as Kotlin constants for the shared code: AppInfo.NAME, AppInfo.ID.
val appInfo =
    tasks.register<GenerateAppInfo>("generateAppInfo") {
        appName = app.name
        appId = app.id
        packageName = "${app.codePackage}.shared"
        outputDir = layout.buildDirectory.dir("generated/appInfo")
    }
kotlin.sourceSets.commonMain { kotlin.srcDir(appInfo.map { it.outputDir }) }

// The Compose resources as the app reads them: the hand-written ones, the same translations again under the names
// other platforms use for their languages (see ResourceQualifiers), and the licences page's list of this module's
// dependencies, exported by AboutLibraries.
val handWrittenResources = layout.projectDirectory.dir("src/commonMain/composeResources")
aboutLibraries {
    export { outputFile = layout.buildDirectory.file("generated/licences/aboutlibraries.json") }
}
val composeResourcesOfApp =
    tasks.register<Sync>("assembleComposeResources") {
        from(handWrittenResources)
        ResourceQualifiers.ALIASES.forEach { (qualifiers, aliases) ->
            aliases.forEach { alias -> from(handWrittenResources.dir("values-$qualifiers")) { into("values-$alias") } }
        }
        from(tasks.named("exportLibraryDefinitions")) { into("files") }
        into(layout.buildDirectory.dir("generated/composeResources"))
    }
compose.resources {
    packageOfResClass = "${app.codePackage}.shared.resources"
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = composeResourcesOfApp.map { layout.projectDirectory.dir(it.destinationDir.absolutePath) },
    )
}

// The iOS app's build settings and Info.plist, from the app's identity, its version and its languages.
val iosApp = rootProject.layout.projectDirectory.dir("iosApp")
tasks.register<GenerateIosProjectFiles>("iosProjectFiles") {
    val version = appVersion
    appName = app.name
    appId = app.id
    marketingVersion = version.release
    buildNumber = version.code
    teamId = providers.environmentVariable("APPLE_TEAM_ID").orElse("")
    defaultLocale = app.locale
    resources = handWrittenResources
    plistTemplate = iosApp.file("App/Info.template.plist")
    xcconfig = iosApp.file("Configuration/Generated.xcconfig")
    plist = iosApp.file("App/Info.plist")
}

// Android Lint checks Android resources only: the translations get the same checks here.
val checkTranslations =
    tasks.register<CheckTranslations>("checkTranslations") {
        resources = handWrittenResources
        defaultLocale = app.locale
        report = layout.buildDirectory.file("reports/translations.txt")
    }
tasks.named("check") { dependsOn(checkTranslations) }

allowOpenSourceLicences()

checkHandWrittenSourcesOnly()

useProjectDetektConfig()
