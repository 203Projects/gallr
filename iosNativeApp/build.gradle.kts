plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
}

kotlin {
    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    iosArm64 {
        binaries.framework {
            // Keep the module name and Kotlin/Native Objective-C names used by the Swift host.
            baseName = "composeApp"
            isStatic = true
            binaryOption("bundleId", "com.gallr.compose")
            export(project(":shared"))
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "composeApp"
            isStatic = true
            binaryOption("bundleId", "com.gallr.compose")
            export(project(":shared"))
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(rootProject.layout.projectDirectory.dir("composeApp/src/applicationMain/kotlin"))
            dependencies {
                api(project(":shared"))
                implementation(libs.lifecycle.viewmodel)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        iosMain {
            kotlin.srcDir(rootProject.layout.projectDirectory.dir("composeApp/src/iosApplicationMain/kotlin"))
        }
    }
}

tasks.register("verifyRendererFreeFramework") {
    description = "Checks the iOS product targets for dependencies on Compose, Skiko, and MapLibre."
    group = "verification"

    doLast {
        val rendererGroups = listOf("org.jetbrains.compose", "androidx.compose", "org.jetbrains.skiko", "org.maplibre")
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val libraries = configurations.getByName("${target}CompileKlibraries")
            val rendererDependencies =
                libraries.incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }.filter { module ->
                    rendererGroups.any { group -> module.group == group || module.group.startsWith("$group.") }
                }
            check(rendererDependencies.isEmpty()) { "$target contains renderer dependencies: $rendererDependencies" }
        }
    }
}
