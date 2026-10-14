plugins {
    id("featureflag.android-library")
    id("featureflag.compose")
}

android {
    namespace = "com.andyyhope.featureflag.ui"
}

dependencies {
    // The companion's runtime: the bridge client, Compose Material 3, and what the screens share
    // and draw with. Nothing else ships to a companion app; see the M3 overview's Global Constraints.
    api(project(":featureflag-android"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Robolectric Compose tests. The rest of the test stack comes from featureflag.android-library.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
