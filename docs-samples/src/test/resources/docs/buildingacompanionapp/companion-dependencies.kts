// A companion app's dependencies, as BuildingACompanionApp.md shows them. Not compiled: DocsSyncTest
// checks the coordinates, and docs-samples itself depends on the same Compose artifacts to run the samples.

// region snippet: companion-install
dependencies {
    implementation(platform("com.andyyhope.featureflag:featureflag-bom:0.1.0"))
    implementation("com.andyyhope.featureflag:featureflag-ui")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
// endregion
