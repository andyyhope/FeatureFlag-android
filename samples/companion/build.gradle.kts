plugins {
    id("featureflag.android-application")
    id("featureflag.compose")
}

// The host this companion connects to: the same property the host's applicationId comes from.
val hostId: String = providers.gradleProperty("featureflag.sample.hostId").get()

android {
    namespace = "com.andyyhope.featureflag.sample.companion"
    defaultConfig {
        applicationId = "com.andyyhope.featureflag.sample.companion"
        versionCode = 1
        versionName = "1.0"
        // Fills the manifest's <uses-permission> for the host's bridge permission.
        manifestPlaceholders["hostId"] = hostId
        buildConfigField("String", "HOST_PACKAGE", "\"$hostId\"")
    }
    buildFeatures {
        buildConfig = true
    }
    // region snippet: sample-companion-signing
    signingConfigs {
        // The key the host is signed with too, so Android grants the host's signature permission.
        getByName("debug") {
            storeFile = file("../keys/shared-debug.keystore")
            storePassword = "android"
            keyAlias = "sample"
            keyPassword = "android"
        }
        // Any other key: the host must refuse this build with PermissionDenied.
        create("untrusted") {
            storeFile = file("../keys/untrusted.keystore")
            storePassword = "android"
            keyAlias = "untrusted"
            keyPassword = "android"
        }
    }
    buildTypes {
        create("untrusted") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("untrusted")
            // Installs beside the trusted companion instead of replacing it.
            applicationIdSuffix = ".untrusted"
            // The library modules have only debug and release.
            matchingFallbacks.add("debug")
        }
    }
    // endregion
}

dependencies {
    // region snippet: sample-companion-dependencies
    // featureflag-ui is the whole companion, and brings the bridge client (featureflag-android) with it.
    implementation(project(":featureflag-ui"))
    implementation(project(":samples:signals"))
    // endregion

    // setContent and the app's own MaterialTheme: featureflag-ui keeps its Compose dependencies internal.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // Robolectric Compose tests. The rest of the test stack comes from featureflag.android-application.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
