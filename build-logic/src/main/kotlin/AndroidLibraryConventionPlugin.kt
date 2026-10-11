import com.android.build.api.dsl.LibraryExtension
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
 * `featureflag.android-library` — baseline config for every Android library module.
 *
 * AGP 9 wires Kotlin itself (built-in Kotlin), so this never applies `kotlin-android`; it
 * configures the Kotlin extension AGP registers. Unit tests run under Robolectric on a
 * JDK 21 test JVM, which Robolectric 4.16 needs for SDK 36.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("featureflag.detekt")
        pluginManager.apply("featureflag.fixtures")

        extensions.configure<LibraryExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
            }
        }

        // Build on JDK 21, emit bytecode for JDK 17, exactly as featureflag.jvm-library does.
        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(JDK_TOOLCHAIN)
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }

        // detekt's default source is src/{main,test}/{java,kotlin}. With built-in Kotlin it
        // cannot discover Android source sets, so name every one the gate must read.
        extensions.configure<DetektExtension> {
            source.setFrom(
                files(
                    "src/main/kotlin",
                    "src/test/kotlin",
                    "src/androidTest/kotlin",
                ),
            )
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
        listOf(
            "kotlin-test-junit",
            "junit",
            "kotlinx-coroutines-test",
            "turbine",
            "androidx-test-core",
            "androidx-test-runner",
            "androidx-test-rules",
            "androidx-test-ext-junit",
        ).forEach { alias ->
            dependencies.add("androidTestImplementation", libs.findLibrary(alias).get())
        }
    }

    private companion object {
        const val COMPILE_SDK = 36
        const val MIN_SDK = 26
        const val JDK_TOOLCHAIN = 21
    }
}
