import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.voicetyping.vxstrm"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    vectorDrawables {
      useSupportLibrary = true
    }
  }

  val envKeystorePath: String? = System.getenv("KEYSTORE_PATH")
  val envStorePassword: String? = System.getenv("STORE_PASSWORD")
  val envKeyPassword: String? = System.getenv("KEY_PASSWORD")
  val envKeyAlias: String = System.getenv("KEY_ALIAS") ?: "upload"

  signingConfigs {
    // Debug signing config strictly gated to debug build type only
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }

    // Release signing config is only created when all required signing credentials are provided
    if (!envKeystorePath.isNullOrBlank() && !envStorePassword.isNullOrBlank() && !envKeyPassword.isNullOrBlank()) {
      create("release") {
        storeFile = file(envKeystorePath)
        storePassword = envStorePassword
        keyAlias = envKeyAlias
        keyPassword = envKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

gradle.taskGraph.whenReady {
  val hasReleaseTask = allTasks.any { task ->
    task.name.contains("Release", ignoreCase = true) &&
    !task.name.contains("test", ignoreCase = true) &&
    !task.name.contains("lint", ignoreCase = true)
  }
  if (hasReleaseTask) {
    val missing = mutableListOf<String>()
    if (System.getenv("KEYSTORE_PATH").isNullOrBlank()) missing.add("KEYSTORE_PATH")
    if (System.getenv("STORE_PASSWORD").isNullOrBlank()) missing.add("STORE_PASSWORD")
    if (System.getenv("KEY_PASSWORD").isNullOrBlank()) missing.add("KEY_PASSWORD")

    if (missing.isNotEmpty()) {
      throw GradleException(
        "Release build failed: Missing required environment variable(s) for release signing: ${missing.joinToString(", ")}. " +
        "Release builds require valid signing credentials and will not fall back to hardcoded or debug keystores."
      )
    }

    val keystoreFile = file(System.getenv("KEYSTORE_PATH"))
    if (!keystoreFile.exists()) {
      throw GradleException(
        "Release build failed: Keystore file not found at KEYSTORE_PATH: ${keystoreFile.absolutePath}"
      )
    }
  }
}

