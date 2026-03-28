import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.timcatworld.platemasker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.timcatworld.platemasker"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["admob_app_id"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val admobAppId = localProperties.getProperty("admob.app.id") ?: "ca-app-pub-3940256099942544~3347511713"
            val bannerAdUnitId = localProperties.getProperty("admob.banner.unit.id") ?: "ca-app-pub-3940256099942544/6300978111"
            
            manifestPlaceholders["admob_app_id"] = admobAppId
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$bannerAdUnitId\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "**/libtensorflowlite_jni.so"
            pickFirsts += "**/libtensorflowlite_run_tensor_jni.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "lib/**/libopencv_java4.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // 最新の LiteRT 2.1.3 を使用し、競合する古い API を除外
    implementation("com.google.ai.edge.litert:litert:2.1.3") {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
    // Support ライブラリも最新の安定版へ
    implementation("com.google.ai.edge.litert:litert-support:1.4.2") {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }

    implementation(project(":opencv"))
    implementation(libs.play.services.ads)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
