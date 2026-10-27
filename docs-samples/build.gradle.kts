plugins {
    id("featureflag.android-library")
    id("featureflag.compose")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.andyyhope.featureflag.docs"
}

// Every code sample in README.md and docs/ lives in src/test, compiled against the libraries as an app
// gets them, with KSP running over it as it runs over an app's flags. Nothing here is published.
dependencies {
    kspTest(project(":featureflag-ksp"))
    testImplementation(project(":featureflag-core"))
    testImplementation(project(":featureflag-test"))
    testImplementation(project(":featureflag-android"))
    testImplementation(project(":featureflag-ui"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.material3)
    testImplementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.lifecycle.runtime.compose)
}

// DocsSyncTest compares the markdown with the code it quotes. The repository root and the version reach
// the test as system properties, and every file it reads is a task input, so editing a doc re-runs it:
// the same hand-over featureflag.fixtures uses. DocsRepository walks the markdown and source patterns.
// The sources are inputs as text, not only as compiled classes: a comment or the indent inside a region
// changes what a doc quotes without changing any bytecode. The link targets are inputs too.
val repositoryRoot = rootProject.layout.projectDirectory
val versionName = providers.gradleProperty("VERSION_NAME")

tasks.withType<Test>().configureEach {
    systemProperty("featureflag.docs.root", repositoryRoot.asFile.absolutePath)
    systemProperty("featureflag.docs.versionName", versionName.get())
    inputs.files(
        repositoryRoot.file("README.md"),
        repositoryRoot.file("LICENSE"),
        repositoryRoot.dir("docs").asFileTree.matching { include("*.md") },
    ).withPropertyName("docsMarkdown").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(layout.projectDirectory.dir("src").asFileTree)
        .withPropertyName("docsSampleRegions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Everything under samples/: the sources and build files it quotes, its README, and the keys and
    // screenshots that README links to.
    inputs.files(repositoryRoot.dir("samples").asFileTree.matching { exclude("**/build/**", "**/.gradle/**") })
        .withPropertyName("docsSamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // TheBridge.md shows what this manifest merges into a host, and TheBridgeTest compares the two.
    inputs.file(repositoryRoot.file("featureflag-bridge/src/main/AndroidManifest.xml"))
        .withPropertyName("docsBridgeManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
