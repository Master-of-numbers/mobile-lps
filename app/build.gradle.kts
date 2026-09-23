plugins {
    alias(libs.plugins.mobilelps.android.application)
    alias(libs.plugins.mobilelps.android.compose)
}

android {
    namespace = "dev.mobilelps"

    defaultConfig {
        applicationId = "dev.mobilelps"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(projects.service)
    implementation(projects.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
