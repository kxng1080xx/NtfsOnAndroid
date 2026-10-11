plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ntfsfm.core.transport"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:block"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
