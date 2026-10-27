// A host app's build.gradle.kts additions, as README.md and GettingStarted.md show them. Not compiled:
// DocsSyncTest checks the coordinates and the version, and docs-samples itself builds against the same modules.

// region snippet: install-app
plugins {
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.1"))
    ksp(platform("com.andyyhope.featureflag:featureflag-bom:0.1.1"))

    implementation("com.andyyhope.featureflag:featureflag-android")
    implementation("com.andyyhope.featureflag:featureflag-bridge")
    ksp("com.andyyhope.featureflag:featureflag-ksp")
    testImplementation("com.andyyhope.featureflag:featureflag-test")
}
// endregion
