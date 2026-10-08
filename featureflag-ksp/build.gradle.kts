plugins {
    id("featureflag.jvm-library")
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
}
