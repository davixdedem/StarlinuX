plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)

}

android {

    namespace = "com.magix.pistarlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.magix.pistarlink"
        minSdk = 33
        targetSdk = 34
        versionCode = 15
        versionName = "Pi-Starlink-0.1.4-Nebula"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


    }

    buildFeatures {
        buildConfig = true
        aidl =  true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            buildFeatures.aidl = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.runtime.saved.instance.state)
    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.androidx.constraintlayout.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.gltfio.android)
    implementation(libs.filament.utils.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.cardview)
    implementation(libs.slidetoact)
    implementation(libs.switch.button)
    implementation(libs.minidns.hla)
    implementation(libs.tunnel)
}