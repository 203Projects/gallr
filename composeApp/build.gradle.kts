import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

// Locate an SPM-resolved xcframework in DerivedData by name.
// Returns the path to the correct slice directory for cinterop -F flags.
fun nmapsXcframeworkSlice(xcframeworkName: String, slice: String): String {
    val derivedData = File(System.getProperty("user.home"), "Library/Developer/Xcode/DerivedData")
    val xcframework = derivedData.walkTopDown()
        .maxDepth(14)
        .firstOrNull { it.isDirectory && it.name == "$xcframeworkName.xcframework" && !it.path.contains("__MACOSX") }
        ?: error(
            "$xcframeworkName.xcframework not found in DerivedData.\n" +
            "Open iosApp in Xcode and do one build to resolve SPM packages."
        )
    return xcframework.resolve(slice).absolutePath
}

fun nmapsFrameworkSlice(slice: String): String = nmapsXcframeworkSlice("NMapsMap", slice)
fun nmapsGeometrySlice(slice: String): String = nmapsXcframeworkSlice("NMapsGeometry", slice)

// Path to stub frameworks that satisfy missing SDK references on Xcode 26.
// UIUtilities.framework is referenced by UIKitDefines.h but not shipped in the
// iPhoneSimulator 26 SDK. The stub satisfies the #import without providing real symbols.
val cinteropStubsDir: String = project.file("src/nativeInterop/stubs").absolutePath

// Returns the SDK sysroot via xcrun so cinterop uses the correct system headers.
fun xcrunSdkPath(sdk: String): String =
    ProcessBuilder("xcrun", "--sdk", sdk, "--show-sdk-path")
        .start()
        .inputStream
        .bufferedReader()
        .readLine()
        ?.trim()
        ?: error("xcrun failed to locate SDK: $sdk")

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosX64 {
        binaries.framework { baseName = "composeApp"; isStatic = true }
        compilations.getByName("main") {
            val NMapsMap by cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/NMapsMap.def"))
                compilerOpts("-F", nmapsFrameworkSlice("ios-arm64_x86_64-simulator"), "-F", nmapsGeometrySlice("ios-arm64_x86_64-simulator"), "-F", cinteropStubsDir, "-isysroot", xcrunSdkPath("iphonesimulator"), "-fno-modules")
            }
        }
    }
    iosArm64 {
        binaries.framework { baseName = "composeApp"; isStatic = true }
        compilations.getByName("main") {
            val NMapsMap by cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/NMapsMap.def"))
                compilerOpts("-F", nmapsFrameworkSlice("ios-arm64"), "-F", nmapsGeometrySlice("ios-arm64"), "-F", cinteropStubsDir, "-isysroot", xcrunSdkPath("iphoneos"), "-fno-modules")
            }
        }
    }
    iosSimulatorArm64 {
        binaries.framework { baseName = "composeApp"; isStatic = true }
        compilations.getByName("main") {
            val NMapsMap by cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/NMapsMap.def"))
                compilerOpts("-F", nmapsFrameworkSlice("ios-arm64_x86_64-simulator"), "-F", nmapsGeometrySlice("ios-arm64_x86_64-simulator"), "-F", cinteropStubsDir, "-isysroot", xcrunSdkPath("iphonesimulator"), "-fno-modules")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(project(":shared"))
            // Supabase auth/postgrest accessible via :shared module dependency
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.datastore.preferences.core)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.naver.map.sdk)
            implementation(libs.naver.map.compose)
            implementation(libs.coil.network.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.coil.network.ktor)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.gallr.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    val keyProps = Properties().also { props ->
        val f = rootProject.file("key.properties")
        if (f.exists()) props.load(f.inputStream())
    }

    signingConfigs {
        create("release") {
            storeFile = file(keyProps.getProperty("storeFile", ""))
            storePassword = keyProps.getProperty("storePassword", "")
            keyAlias = keyProps.getProperty("keyAlias", "")
            keyPassword = keyProps.getProperty("keyPassword", "")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.gallr.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 8
        versionName = "1.4.0"

        // Read Supabase credentials from local.properties (gitignored)
        val localProps = Properties().also { props ->
            val f = rootProject.file("local.properties")
            if (f.exists()) props.load(f.inputStream())
        }
        buildConfigField("String", "SUPABASE_URL",
            "\"${localProps.getProperty("supabase.url", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY",
            "\"${localProps.getProperty("supabase.anon.key", "")}\"")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
