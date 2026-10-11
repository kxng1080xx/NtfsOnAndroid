pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NtfsFileManager"
include(
    ":app",
    ":core:block",
    ":core:vfs",
    ":core:transport",
    ":core:ntfs",
    ":core:fat32",
    ":core:transfer",
    ":core:data",
)
