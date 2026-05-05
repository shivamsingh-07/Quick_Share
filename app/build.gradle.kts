import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.quickshare.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quickshare.tv"
        // Android 11 (API 30) is the floor:
        //  - one uniform storage backend across every supported version
        //    (MediaStore.Downloads is fully usable from API 29 — we go to 30 to
        //    keep the storage / scoped-storage rules simple and uniform)
        //  - guarantees granular network APIs (ConnectivityManager.NetworkCallback)
        //  - matches the install base of currently supported Android-TV devices
        minSdk = 30
        targetSdk = 34
        // Keep in sync with release/versioning updates.
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "VERBOSE_PROTOCOL_LOG", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "VERBOSE_PROTOCOL_LOG", "false")
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

    testOptions {
        // android.util.Log and friends throw "not mocked" by default in JVM
        // unit tests. Returning default values lets our pure-logic tests run
        // without pulling in Robolectric.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
        )
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().forEach { task ->
            // We use Java-style builders (`.newBuilder()`) everywhere; the lite
            // runtime keeps the apk small (no descriptor / reflection).
            task.builtins {
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.tv.material)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.protobuf.javalite)

    implementation(libs.qrcode.kotlin)
    implementation(libs.lottie.compose)

    // mDNS via JmDNS — replaces android.net.nsd.NsdManager because several TV
    // SoCs (older Mediatek/Amlogic boxes) ship a buggy mDNSResponder that
    // returns ERROR_RETRY_LATER under load and drops TXT records during
    // multicast pressure. JmDNS owns its own multicast socket so we control
    // retries, timeouts, and TXT decoding end-to-end.
    implementation(libs.jmdns)

    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.compose.material3:material3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
