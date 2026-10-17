package com.araut.ninshu

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class OnDeviceTranslator {

    private fun resolveLanguage(code: String): String {
        val value = code.trim()

        require(value.isNotEmpty()) {
            "Translation language is missing in local.properties."
        }

        return requireNotNull(
            TranslateLanguage.fromLanguageTag(value)
        ) {
            "Unsupported translation language: $value"
        }
    }

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(
                resolveLanguage(
                    BuildConfig.TRANSLATION_SOURCE_LANGUAGE
                )
            )
            .setTargetLanguage(
                resolveLanguage(
                    BuildConfig.TRANSLATION_TARGET_LANGUAGE
                )
            )
            .build()
    )

    fun prepare(): Task<Void> =
        translator.downloadModelIfNeeded(
            DownloadConditions.Builder()
                .requireWifi()
                .build()
        )

    fun translate(text: String): Task<String> =
        translator.translate(text)

    fun close() {
        translator.close()
    }
}