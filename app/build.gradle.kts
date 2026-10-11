plugins {
    id("com.android.application")
}
android {
    namespace = "com.mina.yuedu"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mina.yuedu"
        minSdk = 24
        targetSdk = 35
        versionCode = 309
        versionName = "3.0.7-md3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("debugKey") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugKey")
            isMinifyEnabled = false
        }
        release {
            // 产出 unsigned APK：正式签名由父代理用原签名流程在构建后完成，
            // release 不混用 debugKey，也不在构建脚本内嵌任何密码/密钥。
            signingConfig = null
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
