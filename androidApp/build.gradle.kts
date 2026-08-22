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
val promotionEnabled =
    (
        providers.gradleProperty("promotion.enabled").orNull
            ?: providers.environmentVariable("GALLR_PROMOTION_ENABLED").orNull
            ?: localProps.getProperty("promotion.enabled", "false")
    ).trim().equals("true", ignoreCase = true)
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
val firebaseProjectId =
    providers.gradleProperty("firebase.project.id").orNull
        ?: providers.environmentVariable("GALLR_FIREBASE_PROJECT_ID").orNull
        ?: localProps.getProperty("firebase.project.id", "")
val firebaseApplicationId =
    providers.gradleProperty("firebase.application.id").orNull
        ?: providers.environmentVariable("GALLR_FIREBASE_APPLICATION_ID").orNull
        ?: localProps.getProperty("firebase.application.id", "")
val firebaseApiKey =
    providers.gradleProperty("firebase.api.key").orNull
        ?: providers.environmentVariable("GALLR_FIREBASE_API_KEY").orNull
        ?: localProps.getProperty("firebase.api.key", "")
val firebaseSenderId =
    providers.gradleProperty("firebase.sender.id").orNull
        ?: providers.environmentVariable("GALLR_FIREBASE_SENDER_ID").orNull
        ?: localProps.getProperty("firebase.sender.id", "")

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
        versionCode = 36
        versionName = "1.10.1"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLIC_API_KEY", "\"$supabaseApiKey\"")
        buildConfigField("String", "EXHIBITION_CATALOG_SOURCE", "\"$exhibitionCatalogSource\"")
        buildConfigField("boolean", "PROMOTION_ENABLED", promotionEnabled.toString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"$firebaseApplicationId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"$firebaseSenderId\"")
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
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(kotlin("test-junit"))
}

val validateStoreRelease =
    tasks.register("validateStoreRelease") {
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
            require(firebaseProjectId.isNotBlank()) {
                "Store release requires the Firebase project ID used for gallery alerts"
            }
            require(firebaseApplicationId.isNotBlank()) {
                "Store release requires the Firebase Android application ID used for gallery alerts"
            }
            require(firebaseApiKey.isNotBlank()) {
                "Store release requires the Firebase public API key used for gallery alerts"
            }
            require(firebaseSenderId.isNotBlank()) {
                "Store release requires the Firebase sender ID used for gallery alerts"
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

val verifyReleaseEdgeToEdgeCompatibility =
    tasks.register("verifyReleaseEdgeToEdgeCompatibility") {
        group = "verification"
        description = "Fail if the release retains APIs flagged by Play's Android 15 edge-to-edge checks."
        dependsOn("minifyReleaseWithR8", "processReleaseManifest")

        doLast {
            val mergedManifest =
                layout.buildDirectory
                    .file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml")
                    .get()
                    .asFile
            require(mergedManifest.isFile) { "Release merged manifest was not generated" }
            val mergedManifestText = mergedManifest.readText()
            require("windowLayoutInDisplayCutoutMode" !in mergedManifestText) {
                "Release manifest must not set deprecated display-cutout modes"
            }
            require("android:windowSoftInputMode=\"adjustResize\"" in mergedManifestText) {
                "Release activity must use adjustResize so Compose receives IME insets edge-to-edge"
            }

            val releaseDex =
                layout.buildDirectory
                    .file("intermediates/dex/release/minifyReleaseWithR8/classes.dex")
                    .get()
                    .asFile
            require(releaseDex.isFile) { "Minified release DEX was not generated" }
            val releaseDexText = releaseDex.readBytes().toString(Charsets.ISO_8859_1)
            val retainedDeprecatedSymbols =
                listOf(
                    "setStatusBarColor",
                    "setNavigationBarColor",
                    "layoutInDisplayCutoutMode",
                ).filter(releaseDexText::contains)
            require(retainedDeprecatedSymbols.isEmpty()) {
                "Release retains Play-flagged edge-to-edge symbols: ${retainedDeprecatedSymbols.joinToString()}"
            }
        }
    }

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseEdgeToEdgeCompatibility)
}
