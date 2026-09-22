plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tulipskun.droidssh"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.tulipskun.droidssh"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Stable key สำหรับ sideload: ทุก build เซ็นด้วยคีย์เดิม อัปเดตทับได้
        // keystore ถูก decode จาก droid-ssh-debug.keystore.b64 ใน CI
        create("stable") {
            val stableKeystore = file("droid-ssh-debug.keystore")
            if (stableKeystore.exists()) {
                storeFile = stableKeystore
                storeType = "PKCS12"
                storePassword = "droidsshdebug"
                keyAlias = "droid-ssh-debug"
                keyPassword = "droidsshdebug"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            val stableKeystore = file("droid-ssh-debug.keystore")
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        getByName("release") {
            val stableKeystore = file("droid-ssh-debug.keystore")
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // SSHD server (Apache MINA)
    implementation("org.apache.sshd:sshd-common:2.12.0")
    implementation("org.apache.sshd:sshd-core:2.12.0")
    implementation("org.apache.sshd:sshd-sftp:2.12.0")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")
}
