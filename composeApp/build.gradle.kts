import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

val composeFrameworkBundleId = "com.gallr.compose"

// Locate an SPM-resolved xcframework in DerivedData by name.
// Returns the path to the correct slice directory for cinterop -F flags.
fun nmapsXcframeworkSlice(
    xcframeworkName: String,
    slice: String,
): String {
    val derivedData =
        File(System.getProperty("user.home"), "Library/Developer/Xcode/DerivedData")
    val xcframework =
        derivedData
            .walkTopDown()
            .maxDepth(14)
            .firstOrNull {
                it.isDirectory && it.name == "$xcframeworkName.xcframework" &&
                    !it.path.contains(
                        "__MACOSX",
                    )
            }
            ?: error(
                "$xcframeworkName.xcframework not found in DerivedData.\n" +
                    "Open iosApp in Xcode and do one build to resolve SPM packages.",
            )
    return xcframework.resolve(slice).absolutePath
}

fun nmapsFrameworkSlice(slice: String): String = nmapsXcframeworkSlice("NMapsMap", slice)

fun nmapsGeometrySlice(slice: String): String = nmapsXcframeworkSlice("NMapsGeometry", slice)

// Path to stub frameworks that satisfy missing SDK references on Xcode 26.
// UIUtilities.framework is referenced by UIKitDefines.h but not shipped in the
// iPhoneSimulator 26 SDK. The stub satisfies the #import without providing real symbols.
val cinteropStubsDir: String = project.file("src/nativeInterop/stubs").absolutePath
val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

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
        compilations.getByName("main") {
            @Suppress("ktlint:standard:property-naming")
            val NMapsMap by cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/NMapsMap.def"))
                if (isMacHost) {
                    compilerOpts(
                        "-F",
                        nmapsFrameworkSlice("ios-arm64"),
                        "-F",
                        nmapsGeometrySlice("ios-arm64"),
                        "-F",
                        cinteropStubsDir,
                        "-isysroot",
                        xcrunSdkPath("iphoneos"),
                        "-fno-modules",
                    )
                }
            }
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "composeApp"
            isStatic = true
            binaryOption("bundleId", composeFrameworkBundleId)
        }
        compilations.getByName("main") {
            @Suppress("ktlint:standard:property-naming")
            val NMapsMap by cinterops.creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/NMapsMap.def"))
                if (isMacHost) {
                    compilerOpts(
                        "-F",
                        nmapsFrameworkSlice("ios-arm64_x86_64-simulator"),
                        "-F",
                        nmapsGeometrySlice("ios-arm64_x86_64-simulator"),
                        "-F",
                        cinteropStubsDir,
                        "-isysroot",
                        xcrunSdkPath("iphonesimulator"),
                        "-fno-modules",
                    )
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
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
