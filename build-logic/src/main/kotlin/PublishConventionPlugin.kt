import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `featureflag.publish` — Maven Central publishing (vanniktech) and a Dokka javadoc jar for one module.
 *
 * Apply it last in a module's `plugins {}`, so Dokka sees the Android or Kotlin plugin first. The
 * coordinates are `GROUP`, the project name and `VERSION_NAME`; the module's own `gradle.properties`
 * gives its `POM_DESCRIPTION`. Publications are signed only when the `signingInMemoryKey` Gradle
 * property is supplied, so a release-version `publishToMavenLocal` dry run needs no key. A
 * `java-platform` project (the BOM) has no sources, so it gets no Dokka.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        if (!pluginManager.hasPlugin("java-platform")) {
            pluginManager.apply("org.jetbrains.dokka")
        }
        pluginManager.apply("com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            pom {
                name.set(project.name)
                inceptionYear.set("2026")
                url.set(REPO_URL)
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("andyyhope")
                        name.set("Andy Hope")
                        url.set("https://github.com/andyyhope/")
                    }
                }
                scm {
                    url.set(REPO_URL)
                    connection.set("scm:git:git://github.com/andyyhope/FeatureFlag-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/andyyhope/FeatureFlag-android.git")
                }
            }
        }
    }

    private companion object {
        const val REPO_URL = "https://github.com/andyyhope/FeatureFlag-android"
    }
}
