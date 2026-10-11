import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** `featureflag.jvm-library` — baseline config for every Kotlin/JVM library module. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("featureflag.detekt")
        pluginManager.apply("featureflag.fixtures")

        // Build on JDK 21, emit bytecode + API surface for JDK 17 consumers.
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(JDK_TOOLCHAIN)
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
                freeCompilerArgs.add("-Xjdk-release=$JDK_RELEASE")
            }
        }
        // Keep javac on the same target as kotlinc so Kotlin's JVM-target validation passes.
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(JDK_RELEASE)
        }

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        listOf("kotlin-test-junit", "junit", "kotlinx-coroutines-test", "turbine").forEach { alias ->
            dependencies.add("testImplementation", libs.findLibrary(alias).get())
        }
    }

    private companion object {
        const val JDK_TOOLCHAIN = 21
        const val JDK_RELEASE = 17
    }
}
