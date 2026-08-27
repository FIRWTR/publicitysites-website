import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

// A plain Kotlin/JVM module for the same reason as :protocol:meshcore — the
// Meshtastic client protocol is protobuf over a byte pipe and needs nothing
// from the Android SDK. protobuf-javalite is the same runtime the Android build
// would use, so the generated code is identical either way.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol:api"))
    api(project(":core:model"))
    api(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // "lite" keeps the generated code small enough for a mobile app;
                // it is what the Meshtastic firmware and clients target.
                id("java") {
                    option("lite")
                }
            }
        }
    }
}
