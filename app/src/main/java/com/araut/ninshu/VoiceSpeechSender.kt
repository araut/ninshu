package com.araut.ninshu

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngine
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

class VoiceSpeechSender(
    context: Context,
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
    private val setBusy: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val outputFile = File(context.cacheDir, "voice-test.wav")
    private val lock = Any()

    private var ready = false
    private var closed = false
    private var activeId: String? = null
    private var audio = ByteArrayOutputStream()
    private var sampleRate = 0
    private var encoding = 0
    private var channels = 0

    private var engine: RtcEngine? = null
    private var trackId = -1
    private var sendJob: Job? = null

    private val tts = TextToSpeech(context.applicationContext) { result ->
        main.post {
            if (!closed) {
                ready = result == TextToSpeech.SUCCESS
                if (!ready) report("Unable to initialize text-to-speech.")
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onBeginSynthesis(
                    utteranceId: String?,
                    sampleRateInHz: Int,
                    audioFormat: Int,
                    channelCount: Int
                ) {
                    synchronized(lock) {
                        if (utteranceId == activeId) {
                            sampleRate = sampleRateInHz
                            encoding = audioFormat
                            channels = channelCount
                        }
                    }
                }

                override fun onAudioAvailable(
                    utteranceId: String?,
                    bytes: ByteArray?
                ) {
                    synchronized(lock) {
                        if (utteranceId == activeId && bytes != null) {
                            audio.write(bytes)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    val captured = synchronized(lock) {
                        if (utteranceId != activeId) return
                        CapturedAudio(
                            audio.toByteArray(),
                            sampleRate,
                            encoding,
                            channels
                        )
                    }

                    main.post {
                        if (utteranceId == activeId && !closed) {
                            transmit(captured)
                        }
                    }
                }

                @Deprecated("Required by Android TTS")
                override fun onError(utteranceId: String?) {
                    fail(utteranceId, "Voice speech synthesis failed.")
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int
                ) {
                    fail(
                        utteranceId,
                        "Voice speech synthesis failed: $errorCode"
                    )
                }
            }
        )
    }

    // Call from the main thread.
    fun send(rtc: RtcEngine, customTrackId: Int, text: String) {
        if (text.isBlank()) {
            report("Set SPEECH_TEST_TEXT in local.properties and rebuild.")
            return
        }

        if (!ready) {
            report("Text-to-speech is still initializing.")
            return
        }

        if (activeId != null) return

        val voice = tts.voices
            ?.filter {
                it.locale.language == "hi" &&
                        !it.isNetworkConnectionRequired &&
                        !it.features.orEmpty().contains("notInstalled")
            }
            ?.sortedBy { it.name }
            ?.firstOrNull()

        if (voice == null) {
            report("Install an offline Language voice in Android TTS settings.")
            return
        }

        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            report("Unable to select the offline language voice.")
            return
        }

        engine = rtc
        trackId = customTrackId

        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            activeId = id
            audio = ByteArrayOutputStream()
            sampleRate = 0
            encoding = 0
            channels = 0
        }

        setBusy(true)
        report("Generating Voice speech locally…")

        // Generates audio without playing it through this phone's speaker.
        val result = tts.synthesizeToFile(
            text,
            Bundle(),
            outputFile,
            id
        )

        if (result != TextToSpeech.SUCCESS) {
            finish("Unable to start speech synthesis: $result")
            return
        }

        // Recover if the TTS engine never completes.
        sendJob = scope.launch {
            delay(20_000)
            if (activeId == id) {
                tts.stop()
                finish("Speech synthesis timed out. Try again.")
            }
        }
    }

    private fun transmit(captured: CapturedAudio) {
        sendJob?.cancel()

        val rtc = engine ?: return
        val currentTrack = trackId

        sendJob = scope.launch {
            try {
                val pcm = withContext(Dispatchers.Default) {
                    toMono48k(captured)
                }

                report("Sending Voice audio…")

                // 48 kHz × 10 ms × 2 bytes, mono.
                val frameBytes = 960
                var offset = 0

                while (offset < pcm.size) {
                    ensureActive()

                    val frame = ByteArray(frameBytes)
                    val count = min(frameBytes, pcm.size - offset)
                    pcm.copyInto(frame, 0, offset, offset + count)

                    val result = rtc.pushExternalAudioFrame(
                        frame,
                        rtc.getCurrentMonotonicTimeInMs(),
                        48_000,
                        1,
                        Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
                        currentTrack
                    )

                    check(result == 0) {
                        "Agora audio push failed: $result"
                    }

                    offset += count
                    delay(10)
                }

                finish("Voice audio sent. Confirm it on the other phone.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                finish(exception.message ?: "Unable to send audio.")
            }
        }
    }

    private fun toMono48k(source: CapturedAudio): ByteArray {
        require(source.encoding == AudioFormat.ENCODING_PCM_16BIT) {
            "This test requires a TTS voice producing 16-bit PCM."
        }
        require(source.rate > 0 && source.channels in 1..2) {
            "Unsupported TTS sample rate or channel count."
        }
        require(source.bytes.isNotEmpty()) {
            "The TTS engine returned no audio."
        }

        val samples = ByteBuffer.wrap(source.bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val frameCount = samples.remaining() / source.channels
        require(frameCount > 0)

        val mono = FloatArray(frameCount) { frame ->
            var sum = 0
            repeat(source.channels) { channel ->
                sum += samples.get(frame * source.channels + channel).toInt()
            }
            sum.toFloat() / source.channels
        }

        // Basic linear resampling for this speech prototype.
        val outputCount = (
                frameCount.toLong() * 48_000 / source.rate
                ).toInt().coerceAtLeast(1)

        val output = ByteBuffer.allocate(outputCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        repeat(outputCount) { index ->
            val position = index.toDouble() * source.rate / 48_000
            val left = position.toInt().coerceAtMost(mono.lastIndex)
            val right = min(left + 1, mono.lastIndex)
            val fraction = (position - left).toFloat()
            val value = mono[left] +
                    (mono[right] - mono[left]) * fraction

            output.putShort(
                value.toInt().coerceIn(-32768, 32767).toShort()
            )
        }

        return output.array()
    }

    private fun fail(id: String?, message: String) {
        main.post {
            if (id == activeId && !closed) {
                sendJob?.cancel()
                finish(message)
            }
        }
    }

    private fun finish(message: String) {
        synchronized(lock) {
            activeId = null
            audio.reset()
        }
        engine = null
        setBusy(false)
        report(message)
        outputFile.delete()
    }

    // Call before destroying the Agora engine.
    fun cancel() {
        synchronized(lock) {
            activeId = null
            audio.reset()
        }
        sendJob?.cancel()
        sendJob = null
        tts.stop()
        engine = null
        setBusy(false)
    }

    fun shutdown() {
        closed = true
        cancel()
        tts.shutdown()
        outputFile.delete()
    }

    private data class CapturedAudio(
        val bytes: ByteArray,
        val rate: Int,
        val encoding: Int,
        val channels: Int
    )
}