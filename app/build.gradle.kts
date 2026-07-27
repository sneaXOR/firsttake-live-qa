plugins {
    id("com.android.application")
}

android {
    namespace = "dev.firsttake.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.firsttake.probe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // compile/target SDK 37 is reported by lint but is not distributed by
        // the stable SDK manager used by this reproducible build yet.
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "OldTargetApi",
        )
    }
}

dependencies {
    // Core 1.19 requires compile SDK 37, which is not yet distributed by the
    // stable SDK manager used by this reproducible Windows build.
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.11.0")

    val cameraX = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Official on-device baseline. Missing detections are treated as UNKNOWN;
    // this dependency is never on the capture writer path.
    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    testImplementation("junit:junit:4.13.2")
}
