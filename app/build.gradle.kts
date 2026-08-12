plugins {
    id("com.android.application")
}

val signingStore = providers.gradleProperty("YueduStoreFile").orNull?.let(::file)
val signingStorePassword = providers.gradleProperty("YueduStorePassword").orNull
val signingKeyAlias = providers.gradleProperty("YueduKeyAlias").orNull
val signingKeyPassword = providers.gradleProperty("YueduKeyPassword").orNull
val hasPublishedSigning = signingStore?.isFile == true &&
    signingStorePassword != null && signingKeyAlias != null && signingKeyPassword != null

android {
    namespace = "com.mina.yuedu"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mina.yuedu"
        minSdk = 24
        targetSdk = 35
        versionCode = 300
        versionName = "3.0.0-md3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasPublishedSigning) {
            create("published") {
                storeFile = signingStore!!
                storePassword = signingStorePassword!!
                keyAlias = signingKeyAlias!!
                keyPassword = signingKeyPassword!!
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            if (hasPublishedSigning) signingConfig = signingConfigs.getByName("published")
            isMinifyEnabled = false
        }
        release {
            if (hasPublishedSigning) signingConfig = signingConfigs.getByName("published")
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
