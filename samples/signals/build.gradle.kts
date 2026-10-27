plugins {
    id("featureflag.jvm-library")
}

dependencies {
    // FlagSignal. The host and the companion both depend on this module, as iOS compiles
    // AppSignals.swift into both targets, so the two sides agree on every signal's name.
    api(project(":featureflag-core"))
}
