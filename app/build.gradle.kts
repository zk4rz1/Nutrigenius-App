plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.nutrigeniusbridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.nutrigeniusbridge"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.0"
        // L'app web di produzione, su ServeJS (si cambia dalle impostazioni dell'app, senza ricompilare).
        buildConfigField("String", "WEBAPP_URL", "\"https://nutrigenius.casascoiattolo.com/\"")
        // Da dove l'APK si aggiorna da sé (Aggiornamenti.kt).
        buildConfigField("String", "AGGIORNAMENTI_URL", "\"https://nutrigenius.casascoiattolo.com/apk/latest.json\"")
        manifestPlaceholders["appName"] = "NutriGenius"
    }

    // La chiave di rilascio dalla 1.2.0 (le versioni prima erano firmate con una chiave di debug):
    // file e password si passano a Gradle (-Pnutrigenius.keystore=…, -Pnutrigenius.password=…),
    // non stanno nel repository. Senza, si firma con la chiave di debug.
    signingConfigs {
        val chiave = project.findProperty("nutrigenius.keystore") as String?
        if (chiave != null) {
            create("rilascio") {
                storeFile = file(chiave)
                storeType = "pkcs12"
                storePassword = project.findProperty("nutrigenius.password") as String
                keyAlias = "nutrigenius"
                keyPassword = project.findProperty("nutrigenius.password") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("rilascio") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Health Connect
  implementation(libs.androidx.health.connect.client)
  implementation(libs.androidx.work.runtime)
  
  // Serialization
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.gson)
}
