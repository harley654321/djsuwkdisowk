plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "io.vdl.cloudkit"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        if (name == "compileReleaseKotlin" || name == "compileDebugKotlin") {
            freeCompilerArgs.add("-Xexplicit-api=strict")
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
}

// This module PORTS extractors from recloudstream/cloudstream (GPL-3.0).
// The module itself is therefore GPL-3.0 (see LICENSE-NOTE.md) and is kept
// SEPARATE from the MIT core so the core stays license-clean. Consumers
// who do not add this module lose nothing else.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.vdl"
            artifactId = "extractor-cloudkit"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
