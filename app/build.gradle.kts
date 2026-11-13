import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vCode = project.findProperty("appVersionCode")?.toString()?.toIntOrNull() ?: 1
val vName = project.findProperty("appVersionName")?.toString() ?: "1.0.0"

android {
    namespace = "by.brest103.ssmpplus"
    compileSdk = 34

    defaultConfig {
        applicationId = "by.brest103.ssmpplus"
        minSdk = 24
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }

    signingConfigs {
        create("release") {
            val keystoreFile = File(rootDir, "ssmp-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "SSMP_Brest_2026_Secure"
                keyAlias = "ssmp_plus_key"
                keyPassword = "SSMP_Brest_2026_Secure"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
