import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nazhif.jagoan"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.nazhif.jagoan"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }

            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storePassword = keystoreProperties["storePassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
        }
    }

    buildTypes {
        debug {
            // Defaults to the deployed backend. For local dev, override with:
            //   ./gradlew installDebug -PserverUrl=http://localhost:3000/webhook/transaction
            //   (and run: adb reverse tcp:3000 tcp:3000)
            val debugServerUrl = (project.findProperty("serverUrl") as String?)
                ?: "https://jagoan.nemoris.site/webhook/transaction"
            buildConfigField("String", "SERVER_BASE_URL", "\"$debugServerUrl\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField(
                "String",
                "SERVER_BASE_URL",
                "\"https://jagoan.nemoris.site/webhook/transaction\""
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.androidx.compose.foundation)

    // Lifecycle + SavedState owners — required to host a Compose ComposeView
    // inside a WindowManager overlay (outside an Activity).
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.savedstate)

    // OkHttp for making HTTP requests to the server
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}