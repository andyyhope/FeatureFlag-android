import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `featureflag.compose` — Compose for an Android library module. Apply it after
 * `featureflag.android-library`, which registers the `LibraryExtension` this configures.
 *
 * The compose compiler is a Kotlin compiler plugin released with Kotlin itself, so its version
 * is the catalog's `kotlin`. Compose libraries are added by the module, through the BOM.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            buildFeatures.compose = true
        }
    }
}
