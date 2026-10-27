pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "feature-flags-android"

include(":featureflag-core")
include(":featureflag-ksp")
include(":featureflag-test")
include(":featureflag-android")
include(":featureflag-bridge")
include(":featureflag-ui")
include(":featureflag-bom")
include(":docs-samples")
include(":samples:signals")
include(":samples:host")
include(":samples:companion")
