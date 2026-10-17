package com.araut.ninshu

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

class AutoSpeechRecognizer(
    context: Context,
    private val report: (String) -> Unit,
    setActive: (Boolean) -> Unit,
    onTranscript: (String) -> Unit
) : SpeechInputEngine {

    private val appContext = context.applicationContext

    private var closed = false
    private var requestedLanguage = ""
    private var preferVosk = false
    private var current: SpeechInputEngine? = null

    private val vosk = VoskSpeechRecognizer(
        context = appContext,
        report = { report("Vosk: $it") },
        setActive = setActive,
        onTranscript = onTranscript
    )

    private val android = OnDeviceSpeechRecognizer(
        context = appContext,
        report = { report("Android: $it") },
        setActive = setActive,
        onTranscript = onTranscript,
        onUnsupportedLanguage = {
            fallbackFromAndroid()
        }
    )

    override fun start(languageTag: String) {
        if (closed) return

        val language = languageTag.trim()

        if (language.isBlank()) {
            report("Configure SPEECH_RECOGNITION_LANGUAGE.")
            return
        }

        if (requestedLanguage != language) {
            preferVosk = false
        }

        requestedLanguage = language

        if (!preferVosk && androidAvailable()) {
            current = android
            android.start(language)
        } else {
            useVosk()
        }
    }

    private fun androidAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }

        return try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(
                appContext
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun fallbackFromAndroid() {
        if (!closed && current === android) {
            useVosk()
        }
    }

    private fun useVosk() {
        if (closed) return

        preferVosk = true
        current = vosk
        vosk.start(requestedLanguage)
    }

    override fun stop() {
        current?.stop()
    }

    override fun cancel() {
        current?.cancel()
    }

    override fun shutdown() {
        if (closed) return

        closed = true
        android.shutdown()
        vosk.shutdown()
        current = null
    }
}