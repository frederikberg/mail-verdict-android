plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.ktlint)
}

// versionCode is derived from the one version source, so an upgrade is always accepted by Android:
// 1.2.3 -> 10203. A pre-release suffix ("-rc1") does not change the code.
val appVersionName: String = providers.gradleProperty("appVersionName").get()
val appVersionCode: Int =
    appVersionName
        .substringBefore('-')
        .split('.')
        .map(String::toInt)
        .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// The release keystore reaches the build as a file path and a password in the environment; a
// debug build signs with the SDK's debug key and needs neither.
val releaseKeystoreFile: Provider<String> = providers.environmentVariable("RELEASE_KEYSTORE_FILE")
val releaseKeystorePassword: Provider<String> = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD")

android {
    namespace = "com.frederikberg.mailverdict"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.frederikberg.mailverdict"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystoreFile.isPresent && releaseKeystorePassword.isPresent) {
            create("release") {
                storeFile = file(releaseKeystoreFile.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = "release"
                keyPassword = releaseKeystorePassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

// An unsigned release APK installs nowhere, so a release build without the keystore fails instead of
// producing one.
val requireReleaseSigning =
    tasks.register("requireReleaseSigning") {
        val present = releaseKeystoreFile.isPresent && releaseKeystorePassword.isPresent
        val message = "A release build needs RELEASE_KEYSTORE_FILE and RELEASE_KEYSTORE_PASSWORD in the environment."
        doLast { check(present) { message } }
    }
tasks.matching { it.name == "packageRelease" }.configureEach { dependsOn(requireReleaseSigning) }

dependencies {
    implementation(project(":kit"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.unifiedpush.connector)

    debugImplementation(libs.nanohttpd)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
