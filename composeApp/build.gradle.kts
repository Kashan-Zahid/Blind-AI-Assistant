@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

tasks.register("prepareKotlinBuildScriptModel") {}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    wasmJs {
        compilerOptions {
            moduleName.set("composeApp")
        }

        browser {
            commonWebpackConfig {
                devServer = (
                    devServer
                        ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()
                ).copy(
                    static = (
                        devServer?.static ?: mutableListOf()
                    ).apply {
                        add(project.projectDir.path + "/src/wasmJsMain/resources")
                    }
                )
            }
        }

        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

android {
    namespace = "com.blindassistant"
    compileSdk = 36

    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use { load(it) }
    }
    val geminiApiKey = localProps.getProperty("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: (findProperty("GEMINI_API_KEY") as? String)?.takeIf { it.isNotBlank() }
        ?: ""

    defaultConfig {
        applicationId = "com.blindassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}