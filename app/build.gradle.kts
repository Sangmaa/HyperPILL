import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signingProps: Properties? = rootProject.file("signing.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { load(file.inputStream()) } }

android {
    namespace = "com.karin.hyperpill"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.karin.hyperpill"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (signingProps != null) {
                val storePath = signingProps.getProperty("storeFile")
                storeFile = rootProject.file(storePath)
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Shrinking is opt-in via -Pshrink (see proguard-rules.pro). It is not the
            // default because this module is loaded reflectively into other apps: the
            // hook entry points and every class named only in scope/hook configuration
            // must survive, and a wrong keep rule fails silently at hook-install time
            // rather than at build time.
            if (project.hasProperty("shrink")) {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            signingConfig = if (signingProps != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }

    // The UI only ships Chinese and English, so every other locale that Compose and Miuix
    // package is dead weight in resources.arsc and in res/values-*.
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    // AGP leaves classes.dex stored (uncompressed) for minSdk >= 28 because ART can map it
    // in place. That trades download size for a slightly slower cold start, which is the
    // wrong trade for a GitHub release APK: compressed is ~55% smaller in the artifact.
    packaging {
        dex {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // LSPosed Modern API 102
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // Miuix (HyperOS style)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.nav)
    implementation(libs.navigation.event.compose)

    implementation(libs.focus.api)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}