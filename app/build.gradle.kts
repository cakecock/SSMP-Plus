import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vCode = project.findProperty("appVersionCode")?.toString()?.toIntOrNull() ?: 1
val vName = project.findProperty("appVersionName")?.toString() ?: "3.10.0"

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
            val storePass = System.getenv("KEYSTORE_PASSWORD").orEmpty()
            val alias = System.getenv("KEY_ALIAS").orEmpty()

            if (keystoreFile.exists() && storePass.isNotBlank() && alias.isNotBlank()) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                // В этом keystore пароль ключа совпадает с паролем хранилища.
                // Если позже сделаете другой пароль — заведите KEY_PASSWORD secret.
                keyPassword = storePass
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
