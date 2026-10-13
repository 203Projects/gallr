@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

val composeFrameworkBundleId = "com.gallr.compose"

// `-Pgallr.checkNativeLinkage=true` links the iOS binaries without the Kotlin/Native compiler cache. Only an
// uncached (or release) link resolves every dependency against the versions actually on the classpath; the
// cached debug link CI otherwise runs passes even when one of them was compiled against a different Skiko.
val checkNativeLinkage = providers.gradleProperty("gallr.checkNativeLinkage").orNull == "true"

// The plugin switches the cache off only for the Kotlin release named here and merely warns on any other,
// which would turn the check into a cached link that proves nothing. Move this constant with `kotlin` in
// `gradle/libs.versions.toml`.
val uncachedLinkKotlin = DisableCacheInKotlinVersion.`2_4_20`

if (checkNativeLinkage) {
    val declared = with(uncachedLinkKotlin) { "$major.$minor.$patch" }
    val running = libs.versions.kotlin.get()
    check(running == declared) {
        "The native linkage check is written for Kotlin $declared but the build runs Kotlin $running. " +
            "Set uncachedLinkKotlin in composeApp/build.gradle.kts to the constant for $running. If this " +
            "release has none, run ./gradlew composeApp:linkReleaseFrameworkIosSimulatorArm64 instead."
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    android {
        namespace = "com.gallr.compose"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    iosArm64 {
        binaries.framework {
            baseName = "composeApp"
            isStatic = true
            binaryOption("bundleId", composeFrameworkBundleId)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "composeApp"
            isStatic = true
            binaryOption("bundleId", composeFrameworkBundleId)
        }
    }

    // A dependency compiled against another version of a shared library (Skiko, Compose) otherwise links with a
    // warning and aborts at the first call on device. Failing the link keeps that out of a build.
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.configureEach {
            freeCompilerArgs += "-Xpartial-linkage-loglevel=ERROR"
            if (checkNativeLinkage) {
                disableNativeCache(
                    version = uncachedLinkKotlin,
                    reason = "CI links uncached so binary mismatches between dependencies fail the build.",
                )
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.qrcode.kotlin)
            implementation(libs.coil.compose)
            implementation(libs.reorderable)
            implementation(libs.maplibre.compose)
            implementation(project(":shared"))
            // Supabase auth/postgrest accessible via :shared module dependency
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.datastore.preferences.core)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.coroutines.play.services)
            implementation(libs.play.services.location)
            implementation(libs.coil.network.okhttp)
            // The map library ships no Android renderer and accepts exactly one, with no fallback at run time.
            // OpenGL ES runs on every device at minSdk 26 and on emulators; a Vulkan-only build would lose the
            // map wherever Vulkan is missing. iOS resolves its Metal runtime transitively.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
        }
        iosMain.dependencies {
            implementation(libs.coil.network.ktor)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.zxing.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Spec 089: the composer's status-label table is checked against the shared parity file, so the file is a test input.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    inputs
        .file(rootProject.file("specs/089-personal-routes/contracts/opening-hours-parity.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("openingHoursParityFile")
}
