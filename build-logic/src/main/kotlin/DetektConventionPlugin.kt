import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * `featureflag.detekt` — detekt plus its ktlint-backed formatting rules, with the shared
 * `config/detekt/detekt.yml`. Run the gate with `./gradlew detekt`.
 *
 * detekt reads `src/{main,test}/{java,kotlin}` by default. A module with more source sets
 * names them through [DetektExtension.source], as `featureflag.android-library` does.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            // `rootDir` is a plain File, so this stays configuration-cache safe.
            config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.add("detektPlugins", libs.findLibrary("detekt-formatting").get())

        tasks.withType<Detekt>().configureEach {
            reports {
                html.required.set(true)
                xml.required.set(false)
                sarif.required.set(false)
                md.required.set(false)
                txt.required.set(false)
            }
        }
    }
}
