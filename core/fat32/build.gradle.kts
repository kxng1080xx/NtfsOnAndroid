plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ntfsfm.core.fat32"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:block"))
    api(project(":core:vfs"))
    testImplementation(libs.junit)
}
