plugins {
    alias(libs.plugins.mobilelps.android.library)
}

android {
    namespace = "dev.mobilelps.provider"
}

dependencies {
    api(projects.core)
    implementation(libs.androidx.core.ktx)
}
