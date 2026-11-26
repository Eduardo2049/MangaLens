import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

// kotlin { jvmToolchain(17) }

// Local-only settings: local.properties (git-ignored) > -P gradle property > environment variable.
val localProps = Properties().apply {
  val content = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orNull
  if (content != null) load(content.reader())
}
fun localSetting(name: String, default: String): String =
  localProps.getProperty(name)?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    ?: default
fun buildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Release signing is optional: CI decodes the keystore from secrets; without it the release APK is
// signed with the debug key so a tag still produces an installable APK.
val releaseKeystore = localSetting("KEYSTORE_PATH", "").takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }

android {
  namespace = "com.example"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.aistudio.mangalens.trvis"
    minSdk = 24
    targetSdk = 35
    // CI passes -PVERSION_NAME=<tag without "v"> and -PVERSION_CODE=<run number>.
    versionCode = localSetting("VERSION_CODE", "8").toIntOrNull() ?: 8
    versionName = localSetting("VERSION_NAME", "0.8")

    // Priority: key typed in the app > OPENROUTER_API_KEY (personal builds) > backend proxy.
    // The backend URL is EMPTY by default: a release/CI APK without a backend must ask for the key instead of
    // silently calling the emulator-only address 10.0.2.2. For a local backend on the emulator, put
    // TRANSLATION_API_BASE_URL=http://10.0.2.2:8080 in local.properties (debug builds allow plain HTTP).
    buildConfigField("String", "TRANSLATION_API_BASE_URL", buildString(localSetting("TRANSLATION_API_BASE_URL", "")))
    // Shared secret checked by the backend (MANGALENS_APP_TOKEN). Not a real secret once the APK is public,
    // but it keeps casual scrapers from spending the backend's OpenRouter quota.
    buildConfigField("String", "TRANSLATION_API_TOKEN", buildString(localSetting("TRANSLATION_API_TOKEN", "")))
    // The personal key from local.properties only goes into DEBUG builds (see buildTypes): a release APK is
    // meant to be shared, and anything in BuildConfig can be read back out of the APK.
    buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
    buildConfigField(
      "String", "OPENROUTER_TEXT_MODELS",
      buildString(localSetting("OPENROUTER_TEXT_MODELS", "dots-studio/dots-3-note-preview:free,nvidia/nemotron-3-super-120b-a12b:free,nvidia/nemotron-3-ultra-550b-a55b:free"))
    )
    buildConfigField(
      "String", "OPENROUTER_VISION_MODELS",
      buildString(localSetting("OPENROUTER_VISION_MODELS", "nvidia/nemotron-3.5-super-vl:free,dots-studio/dots-3-note-preview:free,google/gemma-4-31b-it:free"))
    )
    // Free tier: 50 requests/day (1000/day after buying 10 credits once).
    buildConfigField("int", "OPENROUTER_DAILY_LIMIT", localSetting("OPENROUTER_DAILY_LIMIT", "50").toIntOrNull()?.toString() ?: "50")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (releaseKeystore != null) {
      create("release") {
        storeFile = releaseKeystore
        storePassword = localSetting("STORE_PASSWORD", "")
        keyAlias = localSetting("KEY_ALIAS", "upload")
        keyPassword = localSetting("KEY_PASSWORD", "")
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
    }
    debug {
      buildConfigField("String", "OPENROUTER_API_KEY", buildString(localSetting("OPENROUTER_API_KEY", "")))
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }

  // Reverted to standard APK name to ensure build stability on AGP 9.1.1
  // Use the default app-debug.apk location
}

ksp {
  // Room writes the schema of each database version here. Commit app/schemas/*.json: it is the record
  // future migrations are checked against.
  arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
  // Live API tests (LiveTranslationTest) only run with MANGALENS_LIVE_TEST=1; changing it must re-run tests.
  inputs.property("liveTest", providers.environmentVariable("MANGALENS_LIVE_TEST").orElse("0"))
  testLogging {
    events("failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStandardStreams = providers.environmentVariable("MANGALENS_LIVE_TEST").orNull == "1"
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences) // unused: preferences live in SharedPreferences
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose) // unused: no remote images are loaded
  // implementation(libs.converter.moshi) // unused: the client is OkHttp + org.json
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor) // unused: the client is OkHttp + org.json
  // On-device OCR: exact text positions for free, so only text (not images) goes to the LLM.
  implementation(libs.mlkit.text.recognition)
  implementation(libs.mlkit.text.recognition.japanese)
  implementation(libs.mlkit.text.recognition.korean)
  implementation(libs.mlkit.text.recognition.chinese)
  // implementation(libs.moshi.kotlin) // unused: the client is OkHttp + org.json
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit) // unused: the client is OkHttp + org.json
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen) // unused: the client is OkHttp + org.json
}
