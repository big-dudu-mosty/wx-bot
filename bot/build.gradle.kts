import buildlogic.versions

plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.flowbot.agent"
    compileSdk = versions.compile
    buildToolsVersion = versions.buildTool

    defaultConfig {
        applicationId = "com.flowbot.agent"
        minSdk = versions.mini
        targetSdk = versions.target
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation(project(":automator"))
}
