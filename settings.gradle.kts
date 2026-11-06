// The machinery's own build: its command-line tools, built with its own convention plugins (plugins/, an included
// build here, published for the apps).
pluginManagement {
    includeBuild("plugins")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.danilopianini.gradle-pre-commit-git-hooks") version "2.1.28"
}

// Conventional Commits, checked before every commit (releases are made from them), and the formatting check of the
// tools and the plugins.
gitHooks {
    commitMsg { conventionalCommits() }
    preCommit { tasks("ktlintCheck", ":plugins:ktlintCheck") }
    createHooks(true)
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "app-machinery"

include(":tools:release")
include(":tools:bump")
include(":tools:thread")
