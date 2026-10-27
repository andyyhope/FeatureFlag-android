plugins {
    id("featureflag.jvm-library")
    id("featureflag.publish")
}

dependencies {
    api(project(":featureflag-core"))
}
