plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Crash reports and usage stats switch on when the Firebase config file is added.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val feedUrl = providers.gradleProperty("pulsecast.feedUrl").get()
val admobAppId = providers.gradleProperty("pulsecast.admobAppId").get()
val nativeAdUnitId = providers.gradleProperty("pulsecast.nativeAdUnitId").get()

android {
    namespace = "app.pulsecast"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pulsecast.ai"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        buildConfigField("String", "FEED_URL", "\"$feedUrl\"")
        buildConfigField("String", "NATIVE_AD_UNIT_ID", "\"$nativeAdUnitId\"")
        manifestPlaceholders["admobAppId"] = admobAppId
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Ads: Google Mobile Ads SDK, plus Google's consent form for EU/UK users.
    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
    // Pictures on cards.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Crash reports and anonymous usage stats (active once google-services.json is added).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
}
