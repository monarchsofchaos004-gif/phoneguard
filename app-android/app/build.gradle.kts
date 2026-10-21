plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pg.sentinel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pg.sentinel"
        minSdk = 26
        targetSdk = 33
        versionCode = 2
        versionName = "0.1.1"
    }

    // Release signing: only active when RELEASE_SIGNING_JSON points to an existing file
    // (local release builds). Without it (e.g. CI) the build behaves exactly as before.
    val releaseSigningFile = System.getenv("RELEASE_SIGNING_JSON")?.let { file(it) }?.takeIf { it.exists() }
    if (releaseSigningFile != null) {
        @Suppress("UNCHECKED_CAST")
        val sj = groovy.json.JsonSlurper().parse(releaseSigningFile) as Map<String, Any?>
        signingConfigs {
            create("release") {
                storeFile = file(sj["storeFile"] as String)
                storePassword = sj["storePassword"] as String
                keyAlias = "phoneguard"
                keyPassword = sj["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
