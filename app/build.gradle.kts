import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localConfig = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) {
        configFile.reader(Charsets.UTF_8).use { load(it) }
    }
}

fun buildConfigString(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

android {
    namespace = "com.araut.ninshu"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.araut.ninshu"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "AGORA_APP_ID",
            buildConfigString(localConfig.getProperty("AGORA_APP_ID", ""))
        )
        buildConfigField(
            "String",
            "AGORA_RTC_TOKEN",
            buildConfigString(localConfig.getProperty("AGORA_RTC_TOKEN", ""))
        )
        buildConfigField(
            "String",
            "AGORA_UID",
            buildConfigString(localConfig.getProperty("AGORA_UID", "1001"))
        )

        buildConfigField(
            "String",
            "SPEECH_TEST_TEXT",
            buildConfigString(
                localConfig.getProperty("SPEECH_TEST_TEXT", "")
            )
        )

        buildConfigField(
            "String",
            "TRANSLATION_SOURCE_LANGUAGE",
            buildConfigString(
                requireNotNull(
                    localConfig.getProperty("TRANSLATION_SOURCE_LANGUAGE")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                ) {
                    "Set TRANSLATION_SOURCE_LANGUAGE in local.properties"
                }
            )
        )

        buildConfigField(
            "String",
            "TRANSLATION_TARGET_LANGUAGE",
            buildConfigString(
                requireNotNull(
                    localConfig.getProperty("TRANSLATION_TARGET_LANGUAGE")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                ) {
                    "Set TRANSLATION_TARGET_LANGUAGE in local.properties"
                }
            )
        )

        buildConfigField(
            "String",
            "SPEECH_RECOGNITION_LANGUAGE",
            buildConfigString(
                requireNotNull(
                    localConfig.getProperty("SPEECH_RECOGNITION_LANGUAGE")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                ) {
                    "Set SPEECH_RECOGNITION_LANGUAGE in local.properties"
                }
            )
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("io.agora.rtc:full-sdk:4.7.0")
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}