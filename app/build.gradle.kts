plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.trafficlightdetector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.trafficlightdetector"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        viewBinding = true
    }

    // The .tflite flatbuffer must stay uncompressed so it can be memory-mapped
    // straight from the APK instead of being copied into the heap.
    androidResources {
        noCompress.addAll(listOf("tflite", "tflite.lite"))
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Lint errors fail the build: CI runs :app:lintDebug as a gate.
        abortOnError = true
        // Warnings are reviewed case by case instead of blocking the build.
        warningsAsErrors = false
        // The text report is printed by CI so findings show up in the log.
        textReport = true
        // Only this module is ours to fix; dependency sources are excluded.
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle / MVVM
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // CameraX: Preview + ImageAnalysis
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // TensorFlow Lite runtime (+ optional GPU delegate)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    // API half of the GPU delegate: `GpuDelegate` cannot be constructed without
    // it from 2.16 on (see the comment in gradle/libs.versions.toml).
    implementation(libs.tensorflow.lite.gpu.api)

    testImplementation(libs.junit)

    // Instrumented tests: need a connected device
    // (`gradle :app:connectedDebugAndroidTest`) -- they run the real TFLite runtime,
    // which is exactly where the buffer-reuse bug showed up.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
