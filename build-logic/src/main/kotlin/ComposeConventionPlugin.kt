import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `featureflag.compose` — Compose for an Android module, library or app. Apply it after
 * `featureflag.android-library` or `featureflag.android-application`, which register the extension
 * this configures; both extend `CommonExtension`.
 *
 * The compose compiler is a Kotlin compiler plugin released with Kotlin itself, so its version
 * is the catalog's `kotlin`. Compose libraries are added by the module, through the BOM.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<CommonExtension> {
            buildFeatures.compose = true
        }
    }
}
