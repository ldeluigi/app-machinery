// The Android application module: the entry point around the shared library.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.danilopianini.gradle-kotlin-qa")
    id("org.danilopianini.git-sensitive-semantic-versioning")
    id("app.cash.licensee")
}

gitSemVer {
    versionPrefix = "v"
}

android {
    namespace = app.codePackage
    compileSdk = int("android-compileSdk")
    defaultConfig {
        applicationId = app.id
        // The launcher's label; the screens read the same name from the generated AppInfo.
        resValue("string", "app_name", app.name)
        minSdk = int("android-minSdk")
        targetSdk = int("android-targetSdk")
        versionName = appVersion.name
        versionCode = appVersion.code
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // Release signing material comes from CI secrets; local release builds stay unsigned.
        val keystore = providers.environmentVariable("UPLOAD_KEYSTORE").orNull
        if (keystore != null) {
            create("upload") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("UPLOAD_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("UPLOAD_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(javaVersion)
        targetCompatibility = JavaVersion.toVersion(javaVersion)
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

checkHandWrittenSourcesOnly()

kotlin {
    jvmToolchain(javaVersion)
}

// The phone's per-app language setting lists the languages of the shared library's translations.
val localeConfig =
    tasks.register<GenerateLocaleConfig>("generateLocaleList") {
        resources.from(rootProject.layout.projectDirectory.dir("composeApp/src/commonMain/composeResources"))
        defaultLocale = app.locale
        outputDir = layout.buildDirectory.dir("generated/localeConfig")
    }
androidComponents {
    onVariants { variant -> variant.sources.res?.addGeneratedSourceDirectory(localeConfig, GenerateLocaleConfig::outputDir) }
}

allowOpenSourceLicences()

dependencies {
    "implementation"(platform(lib("compose-bom")))
    "testImplementation"(lib("junit4"))
    "testImplementation"(lib("robolectric"))
}

useProjectDetektConfig()
