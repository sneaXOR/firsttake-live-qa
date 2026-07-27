plugins {
    id("com.android.library")
}

android {
    namespace = "dev.firsttake.liveqa"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "OldTargetApi",
        )
    }
}

dependencies {
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.11.0")

    val cameraX = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.mediapipe:tasks-vision:0.10.29")

    testImplementation("junit:junit:4.13.2")
}
