plugins {
    `java-platform`
    id("featureflag.publish")
}

// One version for every artifact: implementation(platform("com.andyyhope.featureflag:featureflag-bom:<version>")).
dependencies {
    constraints {
        api(project(":featureflag-core"))
        api(project(":featureflag-ksp"))
        api(project(":featureflag-test"))
        api(project(":featureflag-android"))
        api(project(":featureflag-bridge"))
        api(project(":featureflag-ui"))
    }
}
