plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val buildNum = (project.findProperty("buildNum") as String?)?.toIntOrNull() ?: 1
val ghRepo = (project.findProperty("ghRepo") as String?) ?: ""
val ks = file("release.jks")
android {
    namespace = "tv.flashplayer"
    compileSdk = 34
    defaultConfig {
        applicationId = "tv.flashplayer"; minSdk = 28; targetSdk = 34
        versionCode = buildNum; versionName = "1.$buildNum"
        buildConfigField("String", "GH_REPO", "\"$ghRepo\"")
    }
    signingConfigs {
        create("app") {
            if (ks.exists()) { storeFile = ks; storePassword = "flashtv123"; keyAlias = "flashtv"; keyPassword = "flashtv123" }
        }
    }
    buildTypes { getByName("debug") { if (ks.exists()) signingConfig = signingConfigs.getByName("app") } }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
}
