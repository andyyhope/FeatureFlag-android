plugins {
    id("featureflag.android-application")
    id("featureflag.compose")
    alias(libs.plugins.ksp)
}

// One source of truth: the companion reads the same property for its <uses-permission> and its
// BuildConfig.HOST_PACKAGE. No build type adds an applicationIdSuffix, because the bridge's
// authority and permission are derived from the applicationId.
val hostId: String = providers.gradleProperty("featureflag.sample.hostId").get()

android {
    namespace = hostId
    defaultConfig {
        applicationId = hostId
        versionCode = 1
        versionName = "1.0"
    }
    // The debug key both sample apps share, so Android grants the companion the bridge's signature
    // permission. Committed and debug-only: see samples/keys/README.md.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keys/shared-debug.keystore")
            storePassword = "android"
            keyAlias = "sample"
            keyPassword = "android"
        }
    }
}

dependencies {
    // region snippet: sample-host-dependencies
    // featureflag-bridge brings featureflag-android and featureflag-core with it.
    implementation(project(":featureflag-bridge"))
    implementation(project(":samples:signals"))
    ksp(project(":featureflag-ksp"))
    // endregion

    // The screen (A3). Compose versions come from the BOM, as in featureflag-ui.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Robolectric Compose tests (A3). They host the screen in DemoActivity: an app's unit tests run
    // against its own merged manifest, so ui-test-manifest's ComponentActivity would not be found,
    // and debugImplementation would ship an exported test activity. The rest of the test stack
    // comes from featureflag.android-application.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
