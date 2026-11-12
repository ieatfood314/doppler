import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing and this install's defaults live outside the repo, in ~/.config/doppler/:
//   keystore.properties  storeFile/storePassword/keyAlias/keyPassword
//   android.properties   hub=https://... and device=NAME, the first-run defaults baked into the build
fun props(name: String) = Properties().apply {
    val file = file(System.getProperty("user.home") + "/.config/doppler/" + name)
    if (file.exists()) file.inputStream().use { load(it) }
}
val keystoreProps = props("keystore.properties")
val installProps = props("android.properties")

val commitCount: Int = runCatching {
    val git = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    git.inputStream.bufferedReader().readText().trim().toInt().takeIf { git.waitFor() == 0 }
}.getOrNull() ?: 1

android {
    namespace = "ca.heeney.doppler"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.heeney.doppler"
        minSdk = 29
        targetSdk = 35
        versionCode = commitCount
        versionName = "${project.property("dopplerVersion")}.$commitCount"
        buildConfigField("String", "DEFAULT_HUB", "\"${installProps.getProperty("hub", "")}\"")
        buildConfigField("String", "DEFAULT_DEVICE", "\"${installProps.getProperty("device", "")}\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
