plugins {
    id("featureflag.android-library")
    id("featureflag.publish")
}

android {
    namespace = "com.andyyhope.featureflag.android"
}

dependencies {
    // The only runtime dependency: framework APIs only, no AndroidX at runtime.
    api(project(":featureflag-core"))
}
