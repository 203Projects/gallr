import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

val composeFrameworkBundleId = "com.gallr.compose"
val isMacOsHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

fun xcodeDerivedDataRoots(): List<File> {
    val cloudDerivedData = System.getenv("CI_DERIVED_DATA_PATH")?.trim()?.takeIf(String::isNotEmpty)
    return listOfNotNull(
        cloudDerivedData?.let(::File),
        File(System.getProperty("user.home"), "Library/Developer/Xcode/DerivedData"),
    ).distinctBy(File::getAbsolutePath)
}

// Locate an SPM-resolved xcframework in DerivedData by name and return a slice.
fun xcodeXcframeworkSlice(
    xcframeworkName: String,
    slice: String,
): String {
    val derivedDataRoots = xcodeDerivedDataRoots()
    val xcframework =
        derivedDataRoots
            .asSequence()
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().maxDepth(14) }
            .firstOrNull {
                it.isDirectory && it.name == "$xcframeworkName.xcframework" &&
                    !it.path.contains(
                        "__MACOSX",
                    )
            }
            ?: error(
                "$xcframeworkName.xcframework not found in Xcode DerivedData.\n" +
                    "Searched: ${derivedDataRoots.joinToString(File.pathSeparator)}\n" +
                    "Open iosApp in Xcode and do one build to resolve SPM packages.",
            )
    return xcframework.resolve(slice).absolutePath
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
            if (isMacOsHost) {
                linkerOpts("-F", xcodeXcframeworkSlice("MapLibre", "ios-arm64"), "-framework", "MapLibre")
            }
        }
        if (isMacOsHost) {
            binaries.getTest(DEBUG).linkerOpts(
                "-F",
                xcodeXcframeworkSlice("MapLibre", "ios-arm64"),
                "-framework",
                "MapLibre",
                "-rpath",
                xcodeXcframeworkSlice("MapLibre", "ios-arm64"),
            )
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "composeApp"
            isStatic = true
            binaryOption("bundleId", composeFrameworkBundleId)
            if (isMacOsHost) {
                linkerOpts(
                    "-F",
                    xcodeXcframeworkSlice("MapLibre", "ios-arm64_x86_64-simulator"),
                    "-framework",
                    "MapLibre",
                )
            }
        }
        if (isMacOsHost) {
            binaries.getTest(DEBUG).linkerOpts(
                "-F",
                xcodeXcframeworkSlice("MapLibre", "ios-arm64_x86_64-simulator"),
                "-framework",
                "MapLibre",
                "-rpath",
                xcodeXcframeworkSlice("MapLibre", "ios-arm64_x86_64-simulator"),
            )
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
            implementation(
                libs.maplibre.compose
                    .get()
                    .toString(),
            ) {
                exclude(group = "org.maplibre.gl", module = "android-sdk")
            }
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
            implementation(libs.maplibre.android)
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
