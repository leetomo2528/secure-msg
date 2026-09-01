plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yunjelee.securemsg"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yunjelee.securemsg"
        minSdk = 31
        targetSdk = 35
        versionCode = 33
        versionName = "0.18.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Shipping the debug build put a debuggable, publicly-signed
            // default SMS app on the phone; releases are this build type now.
            //
            // R8 stays off for the moment. Room, socket.io-client, lazysodium
            // and the JSON layer all resolve names reflectively, and there is
            // no instrumented run in this project to prove the shrunk app still
            // starts — turning it on unverified would trade a signing problem
            // for a runtime one. Enable it together with keep rules and a real
            // device check, not before.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Deliberately unsigned here: the update from the old debug key
            // needs a v3 rotation lineage, which the Gradle signing config
            // cannot express. tools/release-apk.sh signs the output with
            // apksigner --lineage instead.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room (local DB for cid->phone mapping + blocklist + message cache)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (credentials + keypair storage)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Socket.IO client
    implementation("io.socket:socket.io-client:2.1.0")

    // Lazysodium (libsodium wrapper for Android)
    implementation("com.goterl:lazysodium-android:5.2.0") {
        // The transitive dependency is resolved as a plain JAR. JNA's
        // Android ARM64 native library is only included by its AAR.
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // CameraX + on-device barcode scanning for QR device pairing. ML Kit's
    // bundled model keeps the scan offline — no Play Services download, no
    // image ever leaving the device.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // QR *encoding* for the other direction: this phone as the new device.
    implementation("com.google.zxing:core:3.5.3")

    // JSON
    implementation("org.json:json:20240303")

    // OkHttp (REST calls)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Unit tests run on the host JVM, where the JNA *AAR* ships no desktop
    // dispatcher. The plain jar does; HostSodium then points JNA at a host
    // libsodium so envelope tests exercise real crypto. lazysodium-java is
    // deliberately NOT used here: it redefines com.goterl.lazysodium.Sodium
    // and shadows the Android class CryptoUtil binds to.
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}
