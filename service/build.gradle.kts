plugins {
    alias(libs.plugins.mobilelps.android.library)
}

android {
    namespace = "dev.mobilelps.service"
}

dependencies {
    api(projects.core)
    api(projects.detector)
    implementation(projects.gnss)
    implementation(projects.lbs)
    implementation(projects.sources)
    implementation(projects.provider)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
