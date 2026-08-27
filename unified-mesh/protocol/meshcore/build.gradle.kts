plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain Kotlin/JVM module, not an Android library: the MeshCore
// companion protocol is pure byte handling and has no reason to depend on the
// Android SDK. It reaches the radio through the RadioLinkTransport seam, whose
// BLE implementation lives in :core:bluetooth. That keeps the whole protocol
// implementation unit-testable on the JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol:api"))
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
