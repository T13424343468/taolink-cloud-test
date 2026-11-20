plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tao.tao.link"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tao.tao.link"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.5.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
