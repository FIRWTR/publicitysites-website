plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Orchestration only: which radio is in which slot, reconnect backoff, and
// fanning inbound traffic out to the store, the bridge and the notifier. It
// builds adapters through a factory rather than constructing transports itself,
// which keeps it free of Android and lets the two-radio behaviour — including
// the isolation guarantees — be tested on the JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:bridge"))
    api(project(":protocol:api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
