plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chargescope.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chargescope.hanhan.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.0"
        resourceConfigurations += listOf("zh", "en")
    }

    flavorDimensions += "ui"
    productFlavors {
        create("standard") {
            dimension = "ui"
            buildConfigField("boolean", "HAS_MIUIX_LIB", "false")
            resValue("string", "app_name", "充了个电")
        }
        create("miuix") {
            dimension = "ui"
            applicationIdSuffix = ".miuix"
            versionNameSuffix = "-miuix"
            buildConfigField("boolean", "HAS_MIUIX_LIB", "true")
            resValue("string", "app_name", "充了个电 MIUIX")
        }
    }

    signingConfigs {
        val ksPath = System.getenv("KEYSTORE_FILE")
        if (!ksPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "DebugProbesKt.bin",
            )
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
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    "miuixImplementation"(libs.miuix.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
