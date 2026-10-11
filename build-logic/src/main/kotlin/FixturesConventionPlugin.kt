import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/**
 * `featureflag.fixtures` — hands the shared on-disk fixtures to every `Test` task, JVM and
 * Android unit tests alike (AGP's unit-test tasks are `Test` tasks too).
 *
 * The repo-root `fixtures/` directory goes to tests by path, as the `featureflag.fixtures`
 * system property, and is registered as a task input so editing a fixture re-runs the tests
 * instead of leaving them UP-TO-DATE. `fixtures-android/` holds the Kotlin-written files Swift
 * decodes; tests compare against them, and rewrite them only when Gradle runs with
 * `-Pfeatureflag.writeAndroidFixtures=true`. Both are file trees rather than `inputs.dir(...)`,
 * so a missing directory is simply empty.
 */
class FixturesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val fixturesDir = rootProject.layout.projectDirectory.dir("fixtures")
        val androidFixturesDir = rootProject.layout.projectDirectory.dir("fixtures-android")
        val writeAndroidFixtures = providers.gradleProperty("featureflag.writeAndroidFixtures").orElse("false").get()
        tasks.withType<Test>().configureEach {
            systemProperty("featureflag.fixtures", fixturesDir.asFile.absolutePath)
            systemProperty("featureflag.writeAndroidFixtures", writeAndroidFixtures)
            inputs.files(fixturesDir.asFileTree)
                .withPropertyName("featureflagFixtures")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(androidFixturesDir.asFileTree)
                .withPropertyName("featureflagAndroidFixtures")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}
