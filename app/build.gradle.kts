plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.gabay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gabay"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Add this for native libraries
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // FIXED: Use proper syntax for Kotlin DSL
    androidResources {
        noCompress += listOf("tflite", "task")
    }

    // FIXED: Use proper packaging syntax for Kotlin DSL
    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/x86/libmediapipe_jni.so",
                "lib/x86_64/libmediapipe_jni.so",
                "lib/armeabi-v7a/libmediapipe_jni.so",
                "lib/arm64-v8a/libmediapipe_jni.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/arm64-v8a/libc++_shared.so"
            )
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.annotation)
    implementation(libs.core.ktx)
    implementation(libs.swiperefreshlayout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // glide
    implementation("com.github.bumptech.glide:glide:4.14.2")
    annotationProcessor("com.github.bumptech.glide:compiler:4.14.2")

    // google auth
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.0.1")

    implementation("org.json:json:20240303")

    // for logging network requests
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Supabase Database
    implementation("io.github.jan-tennert.supabase:postgrest-kt:3.0.0")
    implementation("io.github.jan-tennert.supabase:realtime-kt:3.0.0")
    implementation("io.github.jan-tennert.supabase:auth-kt:3.0.0")

    implementation ("androidx.security:security-crypto:1.1.0-alpha06")

    // Kotlin Serialization (for JSON handling)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // CameraX dependencies - UPDATED to latest
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // MediaPipe - FIXED: Use specific stable version instead of latest.release
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Required for MediaPipe - UPDATED version
    implementation("com.google.guava:guava:32.1.3-android")

    // TensorFlow Lite - UPDATED to newer version
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Card view dependency
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.cardview:cardview:1.0.0")
}

configurations.all {
    resolutionStrategy {
        force("androidx.lifecycle:lifecycle-livedata-core:2.7.0")
        force("androidx.lifecycle:lifecycle-livedata:2.7.0")
        force("androidx.lifecycle:lifecycle-runtime:2.7.0")
        force("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
        force("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
        force("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
        force("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")
        force("androidx.lifecycle:lifecycle-common:2.7.0")
    }

    exclude(group = "org.slf4j", module = "slf4j-api")
}