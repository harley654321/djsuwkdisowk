plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room.gradle)
    `maven-publish`
}

android {
    namespace = "io.vdl.core"
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
        // Explicit API governs the shipped ABI (main source set) only, not tests.
        if (name == "compileReleaseKotlin" || name == "compileDebugKotlin") {
            freeCompilerArgs.add("-Xexplicit-api=strict")
        }
    }
}

// Room Gradle plugin: per-variant schema directories. The previous single
// $projectDir/schemas location raced between parallel kspDebug/kspRelease
// tasks (one truncates the JSON while the other deserializes it).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp.logging)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)

    // Real QuickJS engine (extractor). Android variant for the shipped AAR.
    implementation(libs.quickjs.android)

    // Host-aware embed extractors (recloudstream ports): MixDrop, Dood,
    // StreamWish, Uqload, Voe. Used by SourceResolver before the generic
    // HTML/JS resolution paths; unknown hosts cost one domain check.
    implementation(project(":extractor-cloudkit"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    // JVM variant carries jni/linux_x64|macos natives: unit tests execute
    // the REAL QuickJS, exactly like the harness and CI runners do.
    testImplementation(libs.quickjs.jvm)
}

// AGP 8: the release component exists after evaluation, hence afterEvaluate here.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.vdl"
            artifactId = "core"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}

// quickjs-kt ships two variants with IDENTICAL class names but different
// platform code: android (System.loadLibrary from the APK) and jvm
// (extracts jni/<os>_<arch>/libquickjs.so from the classpath). Only the
// jvm actual can load in a unit-test JVM, so its jar is placed FIRST on
// the test classpath — its classes deterministically shadow the AAR's.
tasks.withType<Test>().configureEach {
    // self-referential filter: no configuration lookup, stays lazy
    val original = classpath
    classpath = original.filter { it.name.startsWith("quickjs-kt-jvm") } + original
}
