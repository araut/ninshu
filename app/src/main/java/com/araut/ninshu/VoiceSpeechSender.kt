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
import java.util.Locale
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

    private val targetLocale = Locale.forLanguageTag(
        BuildConfig.TRANSLATION_TARGET_LANGUAGE.trim()
    )

    private var ready = false
    private var closed = false

    @Volatile
    private var activeId: String? = null

    private var audio = ByteArrayOutputStream()
    private var sampleRate = 0
    private var encoding = 0
    private var channels = 0

    private var engine: RtcEngine? = null
    private var trackId = -1
    private var sendJob: Job? = null

    private val tts = TextToSpeech(
        context.applicationContext
    ) { result ->
        main.post {
            if (!closed) {
                ready = result == TextToSpeech.SUCCESS

                if (!ready) {
                    report("Unable to initialize text-to-speech.")
                }
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
                        if (
                            utteranceId != null &&
                            utteranceId == activeId
                        ) {
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
                        if (
                            utteranceId != null &&
                            utteranceId == activeId &&
                            bytes != null
                        ) {
                            audio.write(bytes)
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    val id = utteranceId ?: return

                    val captured = synchronized(lock) {
                        if (id != activeId) return

                        CapturedAudio(
                            bytes = audio.toByteArray(),
                            rate = sampleRate,
                            encoding = encoding,
                            channels = channels
                        )
                    }

                    main.post {
                        if (id == activeId && !closed) {
                            transmit(id, captured)
                        }
                    }
                }

                @Deprecated("Required by Android TTS")
                override fun onError(utteranceId: String?) {
                    fail(
                        utteranceId,
                        "Speech synthesis failed."
                    )
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int
                ) {
                    fail(
                        utteranceId,
                        "Speech synthesis failed: $errorCode"
                    )
                }
            }
        )
    }

    // Call from the main thread.
    fun send(
        rtc: RtcEngine,
        customTrackId: Int,
        text: String
    ) {
        if (closed) return

        if (text.isBlank()) {
            report("No text to synthesize.")
            return
        }

        if (text.length > TextToSpeech.getMaxSpeechInputLength()) {
            report("Text exceeds the speech engine's input limit.")
            return
        }

        if (!ready) {
            report("Text-to-speech is not ready.")
            return
        }

        if (activeId != null) return

        val voice = tts.voices
            ?.filter {
                it.locale.language == targetLocale.language &&
                        !it.isNetworkConnectionRequired &&
                        !it.features.orEmpty().contains("notInstalled")
            }
            ?.sortedWith(
                compareByDescending<android.speech.tts.Voice> {
                    it.locale.toLanguageTag().equals(
                        targetLocale.toLanguageTag(),
                        ignoreCase = true
                    )
                }.thenBy { it.name }
            )
            ?.firstOrNull()

        if (voice == null) {
            report(
                "Install an offline voice for " +
                        "${targetLocale.toLanguageTag()} " +
                        "in Android TTS settings."
            )
            return
        }

        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            report("Unable to select the offline voice.")
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
        report("Generating speech locally…")

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

        sendJob = scope.launch {
            delay(20_000)

            if (activeId == id) {
                // Invalidate callbacks before stopping synthesis.
                synchronized(lock) {
                    activeId = null
                }

                tts.stop()
                finish("Speech synthesis timed out. Try again.")
            }
        }
    }

    private fun transmit(
        id: String,
        captured: CapturedAudio
    ) {
        sendJob?.cancel()

        val rtc = engine ?: return
        val currentTrack = trackId

        sendJob = scope.launch {
            try {
                val pcm = withContext(Dispatchers.Default) {
                    toMono48k(captured)
                }

                ensureActive()
                if (activeId != id) return@launch

                report("Sending generated audio…")

                // 48 kHz × 10 ms × 2 bytes, mono.
                val frameBytes = 960
                var offset = 0

                while (offset < pcm.size) {
                    ensureActive()
                    if (activeId != id) return@launch

                    val frame = ByteArray(frameBytes)
                    val count = min(
                        frameBytes,
                        pcm.size - offset
                    )

                    pcm.copyInto(
                        frame,
                        destinationOffset = 0,
                        startIndex = offset,
                        endIndex = offset + count
                    )

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

                finish(
                    "Audio sent. Confirm it on the other phone."
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (activeId == id) {
                    finish(
                        exception.message ?: "Unable to send audio."
                    )
                }
            }
        }
    }

    private fun toMono48k(
        source: CapturedAudio
    ): ByteArray {
        require(
            source.encoding == AudioFormat.ENCODING_PCM_16BIT
        ) {
            "This prototype requires a voice producing 16-bit PCM."
        }

        require(
            source.rate > 0 && source.channels in 1..2
        ) {
            "Unsupported TTS sample rate or channel count."
        }

        require(source.bytes.isNotEmpty()) {
            "The TTS engine returned no audio."
        }

        require(
            source.bytes.size % (source.channels * 2) == 0
        ) {
            "The TTS engine returned incomplete PCM samples."
        }

        val samples = ByteBuffer.wrap(source.bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val frameCount =
            samples.remaining() / source.channels

        require(frameCount > 0) {
            "The TTS engine returned no complete audio frames."
        }

        val mono = FloatArray(frameCount) { frame ->
            var sum = 0

            repeat(source.channels) { channel ->
                sum += samples.get(
                    frame * source.channels + channel
                ).toInt()
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
            val position =
                index.toDouble() * source.rate / 48_000

            val left = position.toInt()
                .coerceAtMost(mono.lastIndex)

            val right = min(left + 1, mono.lastIndex)
            val fraction = (position - left).toFloat()

            val value = mono[left] +
                    (mono[right] - mono[left]) * fraction

            output.putShort(
                value.toInt()
                    .coerceIn(-32768, 32767)
                    .toShort()
            )
        }

        return output.array()
    }

    private fun fail(
        id: String?,
        message: String
    ) {
        if (id == null) return

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
        trackId = -1
        setBusy(false)
        report(message)
        outputFile.delete()
    }

    // Call from the main thread before destroying Agora.
    fun cancel() {
        synchronized(lock) {
            activeId = null
            audio.reset()
        }

        sendJob?.cancel()
        sendJob = null

        tts.stop()
        engine = null
        trackId = -1
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