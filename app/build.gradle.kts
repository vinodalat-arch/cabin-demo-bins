plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.incabin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.incabin"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    aaptOptions {
        noCompress += "onnx"
        noCompress += "task"
    }
}

tasks.register("verifyAssets") {
    description = "Verify required model assets exist before building"
    val assetsDir = file("src/main/assets")
    val requiredFiles = listOf("yolov8n-pose.onnx", "yolov8n.onnx", "face_landmarker.task")
    doLast {
        requiredFiles.forEach { name ->
            val f = File(assetsDir, name)
            if (!f.exists()) {
                throw GradleException("Missing required asset: $name in ${assetsDir.absolutePath}")
            }
            println("Asset OK: $name (${f.length()} bytes)")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("verifyAssets")
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // MediaPipe Tasks Vision
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // OpenCV Android SDK
    implementation("org.opencv:opencv:4.10.0")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
}
