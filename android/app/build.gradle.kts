plugins {
    id("com.android.application")
}

android {
    namespace = "global.nscb.aether"
    compileSdk = 34

    defaultConfig {
        applicationId = "global.nscb.aether"
        minSdk = 26
        targetSdk = 34
        versionCode = 56
        versionName = "2.7.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("AETHER_KEYSTORE_PATH") ?: "../aether.keystore"
            val ks = file(ksPath)
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("AETHER_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("AETHER_KEY_ALIAS") ?: "aether"
                keyPassword = System.getenv("AETHER_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksPath = System.getenv("AETHER_KEYSTORE_PATH") ?: "../aether.keystore"
            if (file(ksPath).exists()
                && !System.getenv("AETHER_STORE_PASSWORD").isNullOrEmpty()
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // default debug keystore for local sideload
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
