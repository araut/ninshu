package com.araut.ninshu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

class OnDeviceSpeechRecognizer(
    context: Context,
    private val report: (String) -> Unit,
    private val setActive: (Boolean) -> Unit,
    private val onTranscript: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private var stopping = false
    private var timeout: Runnable? = null

    // All public methods must be called on the main thread.
    fun start(languageTag: String) {
        if (recognizer != null) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            report(
                "This recognition implementation requires Android 12+. " +
                        "Typed input remains available."
            )
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            report("Microphone permission is required.")
            return
        }

        val language = languageTag.trim()

        if (language.isBlank()) {
            report("Configure the source language in local.properties.")
            return
        }

        val currentGeneration = ++generation

        try {
            if (
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(
                    appContext
                )
            ) {
                report(
                    "No on-device recognition service is available. " +
                            "Typed input remains available."
                )
                return
            }

            val speech = SpeechRecognizer
                .createOnDeviceSpeechRecognizer(appContext)

            recognizer = speech
            stopping = false

            speech.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (generation == currentGeneration) {
                            report("Listening. Speak a short sentence.")
                        }
                    }

                    override fun onBeginningOfSpeech() {
                        if (generation == currentGeneration) {
                            report("Speech detected…")
                        }
                    }

                    override fun onEndOfSpeech() {
                        if (generation == currentGeneration) {
                            stopping = true
                            report("Finishing recognition…")
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (generation != currentGeneration) return

                        val transcript = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        release()

                        if (transcript.isBlank()) {
                            report("No speech recognized. Try again.")
                        } else {
                            onTranscript(transcript)
                            report(
                                "Transcript ready. Review it, " +
                                        "then tap Translate and Send."
                            )
                        }
                    }

                    override fun onError(error: Int) {
                        if (generation != currentGeneration) return

                        release()
                        report(errorMessage(error))
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit

                    override fun onBufferReceived(buffer: ByteArray?) =
                        Unit

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) = Unit

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) = Unit
                }
            )

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    language
                )
                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    true
                )
                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )
                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    1
                )
            }

            setActive(true)
            report("Starting on-device recognition…")

            timeout = Runnable {
                if (generation == currentGeneration) {
                    cancel()
                    report("Recognition timed out. Try a shorter sentence.")
                }
            }.also {
                main.postDelayed(it, 30_000)
            }

            speech.startListening(intent)
        } catch (exception: Exception) {
            release()
            report(
                "Unable to start recognition: ${exception.message}"
            )
        }
    }

    fun stop() {
        val speech = recognizer ?: return
        if (stopping) return

        stopping = true
        report("Finishing recognition…")

        try {
            speech.stopListening()
        } catch (exception: Exception) {
            cancel()
            report(
                "Unable to finish recognition: ${exception.message}"
            )
        }
    }

    fun cancel() {
        // Invalidate callbacks before cancelling the current request.
        generation++

        try {
            recognizer?.cancel()
        } finally {
            release()
        }
    }

    fun shutdown() {
        cancel()
    }

    private fun release() {
        generation++

        timeout?.let { main.removeCallbacks(it) }
        timeout = null

        val current = recognizer
        recognizer = null
        stopping = false

        try {
            current?.destroy()
        } finally {
            setActive(false)
        }
    }

    private fun errorMessage(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO ->
                "Microphone audio error. Check permissions and try again."

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is required."

            SpeechRecognizer.ERROR_NO_MATCH ->
                "No matching speech recognized. Try again."

            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "No speech detected. Try again."

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "Recognition service is busy. Try again."

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                "The configured source language is not supported " +
                        "by this on-device recognition service."

            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "The source language model is unavailable. " +
                        "Download it through your recognition provider."

            else ->
                "On-device recognition failed: $error"
        }
}