import java.util.Properties

// AGP 9 compiles Kotlin itself (built-in Kotlin), no separate Kotlin plugin
plugins {
    id("com.android.application")
}

// secrets live in local.properties (git-ignored), e.g. kma.authKey=<기상청 API허브 authKey>
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.doorskorea.singkro.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.doorskorea.singkro.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // RK3588 and test tablets are arm64; drops ~80 MB of other-ABI native libs
        ndk { abiFilters += "arm64-v8a" }
        // same name as backend/.env also accepted
        val kmaKey = localProps.getProperty("kma.authKey") ?: localProps.getProperty("KMA_AUTH_KEY", "")
        buildConfigField("String", "KMA_AUTH_KEY", "\"${kmaKey.trim()}\"")
    }

    buildFeatures { buildConfig = true }

    // shared with pc-test/: intent eval cases, region -> KMA grid table
    sourceSets["main"].assets.srcDir("../../eval")
    sourceSets["main"].assets.srcDir("../../data")
    // data/ also holds the 기상청 source xlsx/docx and the build script; ship only the json
    androidResources { ignoreAssetsPatterns += listOf("*.xlsx", "*.docx", "*.py") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in JVM unit tests
    testImplementation("org.json:json:20240303")
}
