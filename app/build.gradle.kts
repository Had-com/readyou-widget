plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.newsfeed.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.newsfeed.widget"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("buildVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            // Committed on purpose — see keystore/README or Task 1 of the self-update plan.
            // Every build (CI and local) must sign with the SAME key or a downloaded "update"
            // APK can never install over the one already running (Android refuses a signature
            // mismatch). Gradle's implicit per-machine debug keystore doesn't guarantee that
            // across CI's fresh-machine-every-run environment.
            storeFile     = file("../keystore/newsfeed-debug.keystore")
            storePassword = "newsfeed-debug"
            keyAlias      = "newsfeed-debug-key"
            keyPassword   = "newsfeed-debug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // Glance (widget)
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Kotlin serialization (for config JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Drag-to-reorder in Compose
    implementation("sh.calvin.reorderable:reorderable:2.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // HTTP client for RSS fetching
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Real HTML DOM parser for full-article extraction (FetchFullArticleCallback) — replaces a
    // regex-based tag stripper that had no way to distinguish an ad/related-content block sitting
    // inside <article> from real body text, only whole noise *tags* like <nav>/<script>.
    implementation("org.jsoup:jsoup:1.17.2")
}
