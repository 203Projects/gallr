import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

subprojects {
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        configure<KtlintExtension> {
            version.set(libsCatalog.findVersion("ktlint").get().requiredVersion)
            outputToConsole.set(true)
            filter {
                exclude("**/generated/**")
                exclude("**/build/**")
            }
        }
    }
}
