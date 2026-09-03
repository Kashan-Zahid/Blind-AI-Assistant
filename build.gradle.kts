plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    alias(libs.plugins.kotlinSerialization) apply false
}
