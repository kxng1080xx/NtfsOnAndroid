plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ntfsfm.core.network"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // smbj's dependencies ship duplicate license/manifest files.
        resources.excludes += listOf("META-INF/versions/**", "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    api(project(":core:vfs"))
    implementation(libs.smbj)
    implementation(libs.commons.net)
    testImplementation(libs.junit)
}
