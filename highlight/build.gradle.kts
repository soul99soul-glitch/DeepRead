plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // (Previously) org.mozilla.rust-android-gradle.rust-android — REMOVED for
    // AGP 9 incompatibility (see document/build.gradle.kts header). Native
    // build driven by the hand-rolled Exec task below.
}

android {
    namespace = "app.amber.highlight"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${layout.buildDirectory.get()}/rustJniLibs/android")
        }
    }
}

// ---------------------------------------------------------------------------
// Rust JNI build — hand-rolled cargo-ndk invocation.
// Mirrors the cargoBuildOfficeParsers / cargoBuildRegexTransformer pattern.
// ---------------------------------------------------------------------------

val androidNdkHome: String =
    providers.environmentVariable("ANDROID_NDK_HOME").orNull
        ?: providers.gradleProperty("android.ndkPath").orNull
        ?: System.getProperty("android.ndk")
        ?: "${System.getenv("ANDROID_HOME") ?: System.getProperty("user.home") + "/Library/Android/sdk"}/ndk/27.0.12077973"

val cargoBuildHighlightParser = tasks.register<Exec>("cargoBuildHighlightParser") {
    group = "rust"
    description = "Build libhighlight_parser.so for arm64-v8a via cargo-ndk"
    workingDir = file("../native/highlight-parser")
    environment("ANDROID_NDK_HOME", androidNdkHome)
    inputs.file(rootProject.file("native/.cargo/config.toml"))
    inputs.file(rootProject.file("native/Cargo.lock"))
    inputs.files(fileTree("../native/highlight-parser") { include("**/*.rs", "Cargo.toml") })
    commandLine = listOf(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "rustc", "--release", "--locked", "--lib", "--verbose",
        "--target-dir", rootProject.file("native/target").absolutePath,
        "--", "-C", "link-arg=-Wl,-z,max-page-size=16384",
        "-C", "link-arg=-Wl,-z,common-page-size=16384",
    )
    doLast {
        // Publish only the owned library; cargo-ndk -o also copies stale sibling .so files.
        val outputDir = layout.buildDirectory.dir("rustJniLibs/android/arm64-v8a").get().asFile
        outputDir.listFiles()?.filter { it.name != "libhighlight_parser.so" }?.forEach { it.delete() }
        copy {
            from(rootProject.file("native/target/aarch64-linux-android/release/libhighlight_parser.so"))
            into(outputDir)
        }
    }
    isIgnoreExitValue = false
    onlyIf {
        val cargoNdkAvailable = try {
            ProcessBuilder("cargo", "ndk", "--version").start().waitFor() == 0
        } catch (_: Throwable) { false }
        if (!cargoNdkAvailable) {
            logger.lifecycle("[rust] cargo-ndk not on PATH; skipping highlight-parser native build (JVM fallback will kick in at runtime)")
        }
        cargoNdkAvailable
    }
}

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn(cargoBuildHighlightParser)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    api(libs.quickjs)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
