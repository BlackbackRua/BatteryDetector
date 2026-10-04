plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.blackback.batterydetector"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.blackback.batterydetector"
        minSdk = 30
        targetSdk = 37
        versionCode = 10003
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 is left at its default behaviour: obfuscate and shrink, but do not
            // run the extra whole-program optimisations.
            //
            // `optimization { enable = true }` used to be set here with a
            // packageScope covering kotlin/kotlinx, and that combination rewrote
            // internal library class hierarchies. The result was a launch crash on
            // every release build:
            //
            //   java.lang.IllegalAccessError: Class kotlin.sequences.d extended by
            //   class y41 is inaccessible
            //     at qh0.<clinit>
            //     at kotlinx.coroutines.m.b
            //
            // A class inheriting from the mangled kotlin.sequences package became
            // inaccessible, so the first Flow/coroutine use crashed. Optimising code
            // the app does not own needs per-library keep rules; this app ships one
            // fixed dependency set and gains little from it, so the default is the
            // safer trade.
            isMinifyEnabled = true
            isShrinkResources = true
            // Keep rules live in src/main/keepRules/rules.keep, which AGP merges
            // automatically. No proguardFiles entry is needed for them.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Required for the Shizuku user service interface (IPrivilegedService.aidl).
        aidl = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}