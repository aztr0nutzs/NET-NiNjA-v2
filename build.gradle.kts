plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.netninja.v21"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.netninja.v21"
    minSdk = 24
    targetSdk = 35
    versionCode = 210
    versionName = "2.1.0"
  }

  buildTypes {
    debug { isMinifyEnabled = false }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
}
