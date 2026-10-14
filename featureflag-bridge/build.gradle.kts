plugins {
    id("featureflag.android-library")
    alias(libs.plugins.android.bcv.bridge)
    id("featureflag.publish")
}

android {
    namespace = "com.andyyhope.featureflag.bridge"
}

dependencies {
    // The provider's only runtime dependency: the source, the contract and the signal channel.
    // Unit and instrumented test dependencies come from featureflag.android-library.
    api(project(":featureflag-android"))
}
