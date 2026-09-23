plugins {
    alias(libs.plugins.mobilelps.android.library)
}

android {
    namespace = "dev.mobilelps.sources"
}

dependencies {
    api(projects.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
