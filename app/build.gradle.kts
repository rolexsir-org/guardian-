import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

/**
 * Production configuration values are never committed.
 *
 * Resolution order: environment variable, then `local.properties` (git ignored),
 * then an empty default. An empty value means "not configured": the build still
 * succeeds and the app fails safe at runtime (no fabricated endpoint, key or id).
 */
val localProperties = Properties().apply {
  val file = rootProject.file("local.properties")
  if (file.exists()) file.inputStream().use { load(it) }
}

fun configurationValue(name: String): String =
  (System.getenv(name) ?: localProperties.getProperty(name) ?: "").trim()

val cloudflareWorkerUrl = configurationValue("CLOUDFLARE_WORKER_URL")
val revenueCatAndroidApiKey = configurationValue("REVENUECAT_ANDROID_API_KEY")
val revenueCatEntitlementId = configurationValue("REVENUECAT_ENTITLEMENT_ID")

/**
 * RevenueCat Test Store key (`test_...`), used for debug builds so purchases can
 * be exercised end to end without a Google Play Console account.
 *
 * RevenueCat hard-fails an app that ships a Test Store key in a release build, so
 * this value is compiled into the debug build type ONLY. Release builds always get
 * REVENUECAT_ANDROID_API_KEY.
 */
val revenueCatTestStoreKey = configurationValue("REVENUECAT_TEST_STORE_KEY")

if (revenueCatTestStoreKey.isNotEmpty() && !revenueCatTestStoreKey.contains("test")) {
  logger.warn(
    "Guardian: REVENUECAT_TEST_STORE_KEY does not look like a Test Store key " +
      "(expected it to contain 'test'). Check you have not pasted a production key."
  )
}

val keystorePath = configurationValue("KEYSTORE_PATH")
val storePasswordValue = configurationValue("STORE_PASSWORD")
val keyAliasValue = configurationValue("KEY_ALIAS")
val keyPasswordValue = configurationValue("KEY_PASSWORD")
val keystoreFile = keystorePath.takeIf { it.isNotEmpty() }?.let { file(it) }
val hasReleaseSigning = keystoreFile != null && keystoreFile.exists() &&
  storePasswordValue.isNotEmpty() && keyAliasValue.isNotEmpty() && keyPasswordValue.isNotEmpty()

android {
  namespace = "com.guardian.safety"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.guardian.safety"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Public configuration only. Secrets (Cloudflare API tokens, AUTH_SECRET,
    // RevenueCat secret keys) are never embedded in the APK.
    buildConfigField("String", "CLOUDFLARE_WORKER_URL", "\"$cloudflareWorkerUrl\"")
    buildConfigField("String", "REVENUECAT_ANDROID_API_KEY", "\"$revenueCatAndroidApiKey\"")
    buildConfigField("String", "REVENUECAT_ENTITLEMENT_ID", "\"$revenueCatEntitlementId\"")
    buildConfigField("String", "EVIDENCE_MIME_ALLOWLIST", "\"image/jpeg,image/png,image/webp,audio/mp4,audio/mpeg,application/pdf,text/plain,application/json\"")
    buildConfigField("int", "EVIDENCE_MAX_BYTES", "5242880")
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = keystoreFile
        storePassword = storePasswordValue
        keyAlias = keyAliasValue
        keyPassword = keyPasswordValue
      }
    }
  }

  buildTypes {
    release {
      // A Test Store key in a release build is a hard error: RevenueCat crashes
      // the app on launch, and test purchases must never reach real users.
      if (revenueCatAndroidApiKey.startsWith("test_") || revenueCatAndroidApiKey.contains("_test_")) {
        throw GradleException(
          "Guardian: REVENUECAT_ANDROID_API_KEY looks like a RevenueCat Test Store key. " +
            "Release builds must use the production Android SDK key (goog_...)."
        )
      }
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      } else {
        logger.warn(
          "Guardian: release signing is not configured (KEYSTORE_PATH/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD). " +
            "assembleRelease will produce an UNSIGNED artifact that cannot be published."
        )
      }
    }
    debug {
      // Uses the standard Android debug keystore managed by the Android Gradle
      // Plugin. Never used for a production artifact.
      isMinifyEnabled = false

      // Debug builds prefer the Test Store key when one is supplied, so the full
      // purchase flow (offering -> paywall -> purchase -> entitlement -> restore)
      // can be demonstrated with no Play Console account and no real charge.
      // Falls back to the normal key so nothing breaks when it is absent.
      if (revenueCatTestStoreKey.isNotEmpty()) {
        buildConfigField("String", "REVENUECAT_ANDROID_API_KEY", "\"$revenueCatTestStoreKey\"")
      }
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
  packaging {
    resources {
      excludes += setOf(
        "/META-INF/AL2.0",
        "/META-INF/LGPL2.1",
        "/META-INF/DEPENDENCIES",
        "/META-INF/INDEX.LIST",
        "/META-INF/LICENSE*",
        "/META-INF/NOTICE*",
        "META-INF/*.kotlin_module",
      )
    }
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
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
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.biometric)
  // SQLCipher for Android is published as an AAR-only artifact; `@aar` is the
  // coordinate the vendor documents and avoids Gradle looking for a JAR.
  implementation("${libs.sqlcipher.android}@aar")
  implementation(libs.androidx.sqlite)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  // Subscriptions. Only the public SDK key is embedded; the entitlement id is
  // configuration, never a secret.
  implementation(libs.revenuecat.purchases)

  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
