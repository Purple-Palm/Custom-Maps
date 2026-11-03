// Every plugin is declared here once so that all modules share one classpath.
// Declaring kotlin-jvm pins the Kotlin Gradle plugin used by AGP's built-in Kotlin support.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
