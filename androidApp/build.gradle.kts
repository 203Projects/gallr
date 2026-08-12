import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

fun validatePublicSupabaseApiKey(rawKey: String): String {
    val key = rawKey.trim()
    if (key.isEmpty()) return key

    require(!key.startsWith("sb_secret_")) {
        "Supabase secret API keys cannot be packaged in a public app"
    }

    val legacyJwtRole =
        runCatching {
            val segments = key.split('.')
            if (segments.size != 3 || !segments[0].startsWith("eyJ")) return@runCatching null
            val payload = String(Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8)
            Regex(""""role"\s*:\s*"([^"]+)"""")
                .find(payload)
                ?.groupValues
                ?.get(1)
        }.getOrNull()
    require(legacyJwtRole != "service_role") {
        "Supabase service role API keys cannot be packaged in a public app"
    }

    return key
}

fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

val reviewedProductionSupabaseUrl = "https://oqrvbstopuppznxqoonp.supabase.co"
val localProps =
    Properties().also { properties ->
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use(properties::load)
    }
val exhibitionCatalogSource =
    providers.gradleProperty("exhibition.catalog.source").orNull
        ?: providers.environmentVariable("GALLR_EXHIBITION_CATALOG_SOURCE").orNull
        ?: localProps.getProperty("exhibition.catalog.source", "legacy")
require(exhibitionCatalogSource in setOf("legacy", "canonical-v2")) {
    "Invalid exhibition catalog source '$exhibitionCatalogSource'; expected 'legacy' or 'canonical-v2'"
}
val supabaseUrl =
    providers.gradleProperty("supabase.url").orNull
        ?: providers.environmentVariable("GALLR_SUPABASE_URL").orNull
        ?: localProps.getProperty("supabase.url", "")
val supabaseApiKey =
    validatePublicSupabaseApiKey(
        firstNonBlank(
            providers.gradleProperty("supabase.publishable.key").orNull,
            providers.environmentVariable("GALLR_SUPABASE_PUBLISHABLE_KEY").orNull,
            localProps.getProperty("supabase.publishable.key"),
            providers.gradleProperty("supabase.anon.key").orNull,
            providers.environmentVariable("GALLR_SUPABASE_ANON_KEY").orNull,
            localProps.getProperty("supabase.anon.key"),
        ),
    )

fun releaseSigningValue(environmentName: String): String =
    providers.environmentVariable(environmentName).orNull.orEmpty()

val releaseStoreFilePath = releaseSigningValue("GALLR_ANDROID_STORE_FILE")
val releaseStorePassword = releaseSigningValue("GALLR_ANDROID_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("GALLR_ANDROID_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("GALLR_ANDROID_KEY_PASSWORD")

android {
    namespace = "com.gallr.app"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    val releaseSigningConfig =
        if (releaseStoreFilePath.isBlank()) {
            null
        } else {
            signingConfigs.create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }

    defaultConfig {
        applicationId = "com.gallr.app"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 27
        versionName = "1.8.1"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLIC_API_KEY", "\"$supabaseApiKey\"")
        buildConfigField("String", "EXHIBITION_CATALOG_SOURCE", "\"$exhibitionCatalogSource\"")
    }

    buildFeatures { buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            releaseSigningConfig?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":shared"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.datastore.preferences.core)
    implementation(libs.kotlinx.coroutines.android)
}

val validateStoreRelease by tasks.registering {
    group = "verification"
    description = "Fail closed unless the Android App Bundle is signed for the reviewed Seoul release."

    doLast {
        require(supabaseUrl == reviewedProductionSupabaseUrl) {
            "Store release must target the reviewed Seoul Supabase project"
        }
        require(supabaseApiKey.isNotBlank()) {
            "Store release requires a public Supabase publishable/anon key"
        }
        require(exhibitionCatalogSource == "canonical-v2") {
            "Store release must use the canonical-v2 exhibition catalogue"
        }
        require(releaseStoreFilePath.isNotBlank() && project.file(releaseStoreFilePath).isFile) {
            "Store release requires the existing registered Android upload keystore"
        }
        require(releaseStorePassword.isNotBlank()) { "Store release requires the Android keystore password" }
        require(releaseKeyAlias.isNotBlank()) { "Store release requires the Android key alias" }
        require(releaseKeyPassword.isNotBlank()) { "Store release requires the Android key password" }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(validateStoreRelease)
}
