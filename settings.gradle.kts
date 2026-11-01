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
        maven(url = "https://jitpack.io") {
            content {
                includeGroup("com.github.aniyomiorg")
                includeGroup("com.github.mihonapp")
            }
        }
    }
}

rootProject.name = "editotsu-anime-extensions"

// every directory under src/<lang>/<name> is one extension module
File(rootDir, "src").listFiles()?.filter { it.isDirectory }?.forEach { lang ->
    lang.listFiles()?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }?.forEach { ext ->
        include(":src:${lang.name}:${ext.name}")
    }
}
