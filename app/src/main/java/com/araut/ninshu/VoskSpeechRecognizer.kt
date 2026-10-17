package com.araut.ninshu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoskSpeechRecognizer(
    context: Context,
    private val report: (String) -> Unit,
    private val setActive: (Boolean) -> Unit,
    private val onTranscript: (String) -> Unit
) : SpeechInputEngine {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    // Main-thread state.
    private var active = false
    private var closed = false
    private var generation = 0

    // Accessed only on the worker thread.
    private var model: Model? = null

    private val sampleRate = 16_000
    private val assetName = BuildConfig.VOSK_MODEL_ASSET.trim()

    override fun start(languageTag: String) {
        if (closed || active) return

        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            report("Microphone permission is required.")
            return
        }

        if (assetName.isBlank()) {
            report("Set VOSK_MODEL_ASSET in local.properties.")
            return
        }

        active = true
        running.set(true)
        cancelled.set(false)

        val request = ++generation
        setActive(true)
        report("Loading offline speech model…")

        worker.execute {
            var recorder: AudioRecord? = null
            var decoder: Recognizer? = null
            var recordingStarted = false
            var transcript = ""
            var failure: String? = null

            try {
                val loadedModel = model ?: loadModel().also {
                    model = it
                }

                if (!cancelled.get() && running.get()) {
                    val speechDecoder = Recognizer(
                        loadedModel,
                        sampleRate.toFloat()
                    )
                    decoder = speechDecoder

                    val minimumBuffer = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )

                    check(minimumBuffer > 0) {
                        "Unsupported microphone audio configuration."
                    }

                    val audioRecorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minimumBuffer, 6_400)
                    )
                    recorder = audioRecorder

                    check(
                        audioRecorder.state ==
                                AudioRecord.STATE_INITIALIZED
                    ) {
                        "Unable to initialize the microphone."
                    }

                    // Cancellation may have occurred during setup.
                    if (!cancelled.get() && running.get()) {
                        audioRecorder.startRecording()
                        recordingStarted = true

                        check(
                            audioRecorder.recordingState ==
                                    AudioRecord.RECORDSTATE_RECORDING
                        ) {
                            "Unable to start microphone recording."
                        }

                        main.post {
                            if (
                                generation == request &&
                                !cancelled.get() &&
                                running.get() &&
                                !closed
                            ) {
                                report(
                                    "Listening offline ($languageTag). " +
                                            "Tap Stop when finished."
                                )
                            }
                        }

                        val pieces = mutableListOf<String>()
                        val buffer = ShortArray(1_600)
                        var samplesRead = 0
                        val maximumSamples = sampleRate * 30

                        while (
                            running.get() &&
                            !cancelled.get() &&
                            samplesRead < maximumSamples
                        ) {
                            val count = audioRecorder.read(
                                buffer,
                                0,
                                buffer.size
                            )

                            check(count > 0) {
                                "Microphone read failed: $count"
                            }

                            samplesRead += count

                            if (
                                speechDecoder.acceptWaveForm(
                                    buffer,
                                    count
                                )
                            ) {
                                textFromJson(speechDecoder.result)
                                    .takeIf { it.isNotBlank() }
                                    ?.let { pieces.add(it) }
                            }
                        }

                        if (!cancelled.get()) {
                            textFromJson(speechDecoder.finalResult)
                                .takeIf { it.isNotBlank() }
                                ?.let { pieces.add(it) }

                            transcript =
                                pieces.joinToString(" ").trim()
                        }
                    }
                }
            } catch (exception: Exception) {
                failure =
                    exception.message ?: "Unknown recognition error."
            } catch (error: LinkageError) {
                failure =
                    "Unable to load Vosk native libraries: " +
                            error.message
            } finally {
                if (recordingStarted) {
                    try {
                        recorder?.stop()
                    } catch (_: IllegalStateException) {
                        // Continue releasing resources.
                    }
                }

                recorder?.release()
                decoder?.close()
                running.set(false)

                val resultText = transcript
                val errorText = failure

                main.post {
                    if (generation == request && !closed) {
                        active = false
                        setActive(false)

                        when {
                            cancelled.get() -> {
                                report("Recognition cancelled.")
                            }

                            errorText != null -> {
                                report(
                                    "Offline recognition failed: $errorText"
                                )
                            }

                            resultText.isBlank() -> {
                                report("No speech recognized. Try again.")
                            }

                            else -> {
                                onTranscript(resultText)
                                report(
                                    "Transcript ready. Review it, " +
                                            "then tap Translate and Send."
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun stop() {
        if (!active || closed) return

        running.set(false)
        report("Finishing offline recognition…")
    }

    override fun cancel() {
        if (!active) return

        cancelled.set(true)
        running.set(false)

        // Remain active until the worker releases the microphone.
    }

    override fun shutdown() {
        if (closed) return

        closed = true
        cancelled.set(true)
        running.set(false)

        // Runs after any current recording operation finishes.
        worker.execute {
            model?.close()
            model = null
        }

        worker.shutdown()
    }

    private fun textFromJson(json: String): String =
        JSONObject(json).optString("text").trim()

    private fun loadModel(): Model {
        require(
            assetName.matches(Regex("[A-Za-z0-9_-]+"))
        ) {
            "VOSK_MODEL_ASSET must be a simple asset folder name."
        }

        val destination = File(
            appContext.filesDir,
            "speech-models/$assetName"
        )
        val readyMarker = File(destination, ".ready")

        if (!readyMarker.exists()) {
            destination.deleteRecursively()

            try {
                copyAssetDirectory(assetName, destination)

                check(
                    File(destination, "am/final.mdl").exists()
                ) {
                    "Model files are missing. " +
                            "Check the assets folder nesting."
                }

                readyMarker.writeText("ready")
            } catch (exception: Exception) {
                destination.deleteRecursively()
                throw exception
            }
        }

        return Model(destination.absolutePath)
    }

    private fun copyAssetDirectory(
        assetPath: String,
        destination: File
    ) {
        val children =
            appContext.assets.list(assetPath).orEmpty()

        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()

            appContext.assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            check(destination.exists() || destination.mkdirs()) {
                "Unable to create model directory."
            }

            children.forEach { child ->
                copyAssetDirectory(
                    "$assetPath/$child",
                    File(destination, child)
                )
            }
        }
    }
}