import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The Google Maps key lives in local.properties (a file that is never committed) as:
//     MAPS_API_KEY=your_key_here
// If the line is missing the app still builds and runs; the map is just blank.
val mapsApiKey: String = run {
    val properties = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    properties.getProperty("MAPS_API_KEY", "").trim().ifEmpty { "MAPS_API_KEY_NOT_SET" }
}

// Signing details for the release build live in keystore.properties (a file that is never committed):
//     storeFile=C:/path/to/secureauth-release.jks
//     storePassword=...
//     keyAlias=...
//     keyPassword=...
// Without that file the release build is still produced, just unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.secureauth.simulator"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.secureauth.simulator"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Filled into the ${MAPS_API_KEY} placeholder of AndroidManifest.xml, so the key is
        // never written into source code
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and renames the code, and the resource shrinker removes unused resources.
            // Extra keep rules, if ever needed, go in src/main/keepRules/rules.keep
            optimization {
                enable = true
            }
            isShrinkResources = true
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        // ViewBinding gives type-safe access to layout views (no findViewById)
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.biometric)
    implementation(libs.zxing.android.embedded)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}