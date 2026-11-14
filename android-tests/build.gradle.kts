// JVM tests for the plugin's Kotlin (resources/android), run with Robolectric so SQLite and Android's classes are real.
// The plugin is normally compiled inside the host app's build; this project only exists to test it:
//   cd android-tests && ./gradlew test
// The few NativePHP classes the plugin imports are stand-ins in stubs/ with the same signatures.
plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.0.0"
}

android {
    namespace = "com.blutrixx.plugins.nativephp_sms"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    sourceSets["main"].java.srcDirs("../resources/android", "stubs")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
