import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// extension identity. versionName = "<lib>.<versionCode>", the format aniyomi-style apps read.
val extLib = 16
val extVersionCode = 3
val extName = "Editotsu Torrents"
val extClass = ".EditotsuTorrents"
val pkg = "eu.kanade.tachiyomi.animeextension.all.editotsutorrents"

android {
    namespace = pkg
    compileSdk = 36

    defaultConfig {
        applicationId = pkg
        minSdk = 26
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "$extLib.$extVersionCode"
        base.archivesName.set("editotsu-all.editotsutorrents-v$versionName")
        manifestPlaceholders += mapOf(
            "appName" to "Aniyomi: $extName",
            "extClass" to extClass,
            "extName" to extName,
            "extLib" to "$extLib",
            "nsfw" to 0,
        )
    }

    // release key: signing.properties in the repo root (local, git-ignored) or EXT_STORE_FILE /
    // EXT_STORE_PASSWORD / EXT_KEY_ALIAS / EXT_KEY_PASSWORD env vars (CI). All four or none.
    // With none, the local debug key is used, unless -PrequireReleaseKey is set (CI), then the build fails.
    val signingFile = rootProject.file("signing.properties")
    val fileProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
    fun signingValue(fileKey: String, envKey: String): String? =
        System.getenv(envKey)?.takeIf { it.isNotEmpty() } ?: fileProps.getProperty(fileKey)?.takeIf { it.isNotEmpty() }
    val signing = mapOf(
        "storeFile" to signingValue("storeFile", "EXT_STORE_FILE"),
        "storePassword" to signingValue("storePassword", "EXT_STORE_PASSWORD"),
        "keyAlias" to signingValue("keyAlias", "EXT_KEY_ALIAS"),
        "keyPassword" to signingValue("keyPassword", "EXT_KEY_PASSWORD"),
    )
    val missing = signing.filterValues { it == null }.keys
    if (missing.isNotEmpty() && missing.size < signing.size) {
        throw GradleException("release signing is partly configured, missing: ${missing.joinToString()}")
    }
    val hasReleaseKey = missing.isEmpty()
    if (!hasReleaseKey && project.hasProperty("requireReleaseKey")) {
        throw GradleException("release key required (-PrequireReleaseKey) but not configured")
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signing.getValue("storeFile")!!)
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }
}

dependencies {
    // everything here is provided by the app at runtime
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.aniyomi.lib)
    compileOnly(libs.okhttp)
    compileOnly(libs.coroutines.core)
    compileOnly(libs.injekt)

    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
