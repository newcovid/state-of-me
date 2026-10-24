plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}
android {
    namespace = "io.github.stateofme"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "io.github.stateofme"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.12.0"
    }
    sourceSets.getByName("main") {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("src/io"))
        res.setSrcDirs(listOf("res"))
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    signingConfigs {
        create("preview") {
            storeFile = file("signing/preview.keystore")
            storePassword = "android"
            keyAlias = "preview"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("preview"); isMinifyEnabled = false }
    }
}
kotlin {

    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("org.jetbrains.compose.foundation:foundation-android:1.12.0")
}
