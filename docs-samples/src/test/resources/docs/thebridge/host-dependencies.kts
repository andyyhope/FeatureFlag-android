// A host app's dependencies on the bridge, as TheBridge.md shows them. Not compiled: DocsSyncTest checks
// the coordinates, and TheBridgeTest checks the manifests beside this file.

// region snippet: bridge-install
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-bridge")
}
// endregion

// region snippet: bridge-debug-only
dependencies {
    implementation("com.andyyhope.featureflag:featureflag-android")
    debugImplementation("com.andyyhope.featureflag:featureflag-bridge")
}
// endregion
