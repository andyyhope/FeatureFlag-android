import com.android.build.api.dsl.ApplicationExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `featureflag.android-application` — baseline config for the sample apps.
 *
 * The same SDK levels, JDK and warnings-as-errors as `featureflag.android-library`, but an app is
 * consumer code: no `explicitApi()`, and no `featureflag.fixtures`. AGP 9 wires Kotlin itself
 * (built-in Kotlin), so this never applies `kotlin-android`. Unit tests run under Robolectric on a
 * JDK 21 test JVM, with the library convention's test dependencies.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("featureflag.detekt")

        extensions.configure<ApplicationExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
            }
        }

        // Build on JDK 21, emit bytecode for JDK 17, exactly as the library conventions do.
        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(JDK_TOOLCHAIN)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }

        // With built-in Kotlin detekt cannot discover Android source sets, so name the two an app has.
        extensions.configure<DetektExtension> {
            source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        listOf(
            "kotlin-test-junit",
            "junit",
            "kotlinx-coroutines-test",
            "turbine",
            "robolectric",
            "androidx-test-core",
            "androidx-test-ext-junit",
        ).forEach { alias ->
            dependencies.add("testImplementation", libs.findLibrary(alias).get())
        }
    }

    private companion object {
        const val COMPILE_SDK = 36
        const val MIN_SDK = 26
        const val TARGET_SDK = 36
        const val JDK_TOOLCHAIN = 21
    }
}
