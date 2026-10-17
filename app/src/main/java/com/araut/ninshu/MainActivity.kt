package com.araut.ninshu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.araut.ninshu.ui.theme.NinshuTheme
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.audio.AudioTrackConfig
import io.agora.rtc2.video.VideoCanvas

class MainActivity : ComponentActivity() {

    private var appId by mutableStateOf(BuildConfig.AGORA_APP_ID)
    private var token by mutableStateOf(BuildConfig.AGORA_RTC_TOKEN)
    private var uidInput by mutableStateOf(BuildConfig.AGORA_UID)

    private val sourceLanguage =
        BuildConfig.TRANSLATION_SOURCE_LANGUAGE.trim()

    private val targetLanguage =
        BuildConfig.TRANSLATION_TARGET_LANGUAGE.trim()

    private val recognitionLanguage =
        BuildConfig.SPEECH_RECOGNITION_LANGUAGE.trim()

    private val channelName = "ninshu-dev"

    private var status by mutableStateOf("Ready")
    private var busy by mutableStateOf(false)
    private var permissionPending by mutableStateOf(false)
    private var joined by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var remoteUid by mutableStateOf<Int?>(null)
    private var sendingSpeech by mutableStateOf(false)

    private var engine by mutableStateOf<RtcEngine?>(null)
    private var customAudioTrackId = -1
    private var session = 0

    private lateinit var voiceSpeechSender: VoiceSpeechSender
    private lateinit var speechRecognizer: SpeechInputEngine

    private var recognitionActive by mutableStateOf(false)
    private var recognitionStatus by mutableStateOf(
        "Ready for speech input."
    )

    private val onDeviceTranslator = OnDeviceTranslator()

    private var modelReady by mutableStateOf(false)
    private var preparingModel by mutableStateOf(false)
    private var translating by mutableStateOf(false)
    private var sourceText by mutableStateOf("")
    private var translatedText by mutableStateOf("")

    private var translationStatus by mutableStateOf(
        "Prepare the translation model over Wi-Fi."
    )

    private var translationRequest = 0

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionPending = false

        if (hasPermissions()) {
            joinCall()
        } else {
            status =
                "Camera and microphone permissions are required."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        voiceSpeechSender = VoiceSpeechSender(
            context = this,
            scope = lifecycleScope,
            report = { message ->
                status = message
                Log.d("NinshuSpeech", message)
            },
            setBusy = { sendingSpeech = it }
        )

        speechRecognizer = AutoSpeechRecognizer(
            context = this,
            report = { message ->
                recognitionStatus = message
                Log.d("NinshuRecognition", message)
            },
            setActive = { recognitionActive = it },
            onTranscript = { transcript ->
                sourceText = transcript
                translatedText = ""
            }
        )

        setContent {
            NinshuTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { padding ->
                    CallScreen(Modifier.padding(padding))
                }
            }
        }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestJoin() {
        if (busy || permissionPending) return

        if (hasPermissions()) {
            joinCall()
        } else {
            permissionPending = true
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun updateSession(
        expectedSession: Int,
        update: () -> Unit
    ) {
        runOnUiThread {
            if (
                session == expectedSession &&
                !isFinishing &&
                !isDestroyed
            ) {
                update()
            }
        }
    }

    private fun checkResult(
        operation: String,
        result: Int
    ) {
        check(result == 0) {
            "$operation failed: $result"
        }
    }

    private fun cancelPendingSpeech() {
        speechRecognizer.cancel()
        translationRequest++

        if (translating) {
            translationStatus =
                "Translation send cancelled because the call changed."
        }

        translating = false
        voiceSpeechSender.cancel()
    }

    private fun joinCall() {
        if (busy || isFinishing || isDestroyed) return

        val id = appId.trim()
        val rtcToken = token.trim()
        val uid = uidInput.trim().toIntOrNull()

        if (
            id.length != 32 ||
            rtcToken.isBlank() ||
            uid == null ||
            uid <= 0
        ) {
            status =
                "Enter a valid App ID, token, and positive numeric UID."
            return
        }

        busy = true
        joined = false
        connected = false
        remoteUid = null
        status = "Connecting…"

        val currentSession = ++session

        try {
            val handler = object : IRtcEngineEventHandler() {

                override fun onJoinChannelSuccess(
                    channel: String?,
                    uid: Int,
                    elapsed: Int
                ) {
                    updateSession(currentSession) {
                        joined = true
                        connected = true
                        status = "Joined $channel as $uid"
                    }
                }

                override fun onUserJoined(
                    uid: Int,
                    elapsed: Int
                ) {
                    updateSession(currentSession) {
                        remoteUid = uid
                        status = "Participant $uid joined"
                    }
                }

                override fun onUserOffline(
                    uid: Int,
                    reason: Int
                ) {
                    updateSession(currentSession) {
                        if (remoteUid == uid) {
                            remoteUid = null
                            cancelPendingSpeech()
                        }

                        status = "Participant $uid left"
                    }
                }

                override fun onConnectionStateChanged(
                    state: Int,
                    reason: Int
                ) {
                    updateSession(currentSession) {
                        connected =
                            state ==
                                    Constants.CONNECTION_STATE_CONNECTED

                        when (state) {
                            Constants.CONNECTION_STATE_CONNECTED -> {
                                status =
                                    "Connected to $channelName"
                            }

                            Constants.CONNECTION_STATE_RECONNECTING -> {
                                cancelPendingSpeech()
                                status = "Reconnecting…"
                            }

                            Constants.CONNECTION_STATE_FAILED -> {
                                cancelPendingSpeech()
                                joined = false
                                status =
                                    "Connection failed ($reason). " +
                                            "Leave and retry."
                            }

                            Constants.CONNECTION_STATE_DISCONNECTED -> {
                                cancelPendingSpeech()
                                joined = false
                                status =
                                    "Disconnected ($reason). " +
                                            "Leave and retry."
                            }

                            else -> {
                                status =
                                    "Connection state: $state, " +
                                            "reason: $reason"
                            }
                        }
                    }
                }

                override fun onTokenPrivilegeWillExpire(
                    token: String?
                ) {
                    updateSession(currentSession) {
                        status =
                            "Token expiring. Leave and enter a new token."
                    }
                }

                override fun onError(err: Int) {
                    updateSession(currentSession) {
                        status =
                            "Agora error: $err. Leave and retry."
                    }
                }
            }

            val config = RtcEngineConfig().apply {
                mContext = applicationContext
                mAppId = id
                mEventHandler = handler
            }

            val rtc = RtcEngine.create(config)
            engine = rtc

            checkResult(
                "enableVideo",
                rtc.enableVideo()
            )

            customAudioTrackId = rtc.createCustomAudioTrack(
                Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
                AudioTrackConfig().apply {
                    enableLocalPlayback = false
                }
            )

            check(customAudioTrackId != -1) {
                "Unable to create custom audio track."
            }

            // Recognition owns microphone capture when requested.
            // Agora publishes only synthesized speech.
            checkResult(
                "disable microphone capture",
                rtc.enableLocalAudio(false)
            )

            val options = ChannelMediaOptions().apply {
                channelProfile =
                    Constants.CHANNEL_PROFILE_COMMUNICATION

                publishCameraTrack = true
                publishMicrophoneTrack = false

                publishCustomAudioTrack = true
                publishCustomAudioTrackId = customAudioTrackId

                enableAudioRecordingOrPlayout = true
                autoSubscribeAudio = true
                autoSubscribeVideo = true
            }

            checkResult(
                "joinChannel",
                rtc.joinChannel(
                    rtcToken,
                    channelName,
                    uid,
                    options
                )
            )
        } catch (exception: Exception) {
            closeCall()
            status =
                exception.message ?: "Unable to start call."
        }
    }

    private fun canSendSpeech(): Boolean =
        joined &&
                connected &&
                remoteUid != null &&
                !recognitionActive &&
                !translating &&
                !sendingSpeech &&
                customAudioTrackId != -1

    private fun startSpeechInput() {
        if (!canSendSpeech()) return

        speechRecognizer.start(recognitionLanguage)
    }

    private fun sendVoiceTest() {
        val rtc = engine ?: return
        if (!canSendSpeech()) return

        val text = BuildConfig.SPEECH_TEST_TEXT

        if (text.isBlank()) {
            status =
                "Set SPEECH_TEST_TEXT in local.properties and rebuild."
            return
        }

        voiceSpeechSender.send(
            rtc = rtc,
            customTrackId = customAudioTrackId,
            text = text
        )
    }

    private fun prepareTranslation() {
        if (preparingModel || modelReady) return

        preparingModel = true
        translationStatus =
            "Preparing translation model over Wi-Fi…"

        onDeviceTranslator.prepare()
            .addOnSuccessListener {
                if (!isDestroyed && !isFinishing) {
                    preparingModel = false
                    modelReady = true
                    translationStatus =
                        "On-device translation ready."
                }
            }
            .addOnFailureListener { exception ->
                if (!isDestroyed && !isFinishing) {
                    preparingModel = false
                    translationStatus =
                        "Model preparation failed: ${exception.message}"
                }
            }
    }

    private fun translateAndSend() {
        val rtc = engine ?: return
        if (!canSendSpeech() || !modelReady) return

        val input = sourceText.trim()

        if (input.isBlank()) {
            translationStatus =
                "Enter or speak a sentence first."
            return
        }

        if (input.length > 500) {
            translationStatus =
                "Use a sentence under 500 characters."
            return
        }

        val expectedSession = session
        val expectedRequest = ++translationRequest

        translating = true
        translatedText = ""
        translationStatus = "Translating locally…"

        onDeviceTranslator.translate(input)
            .addOnSuccessListener { result ->
                updateSession(expectedSession) {
                    if (
                        translationRequest == expectedRequest
                    ) {
                        translating = false

                        when {
                            engine !== rtc ||
                                    !joined ||
                                    !connected ||
                                    remoteUid == null -> {
                                translationStatus =
                                    "Call unavailable. Try again."
                            }

                            result.isBlank() -> {
                                translationStatus =
                                    "Translation returned no text."
                            }

                            else -> {
                                translatedText = result
                                translationStatus =
                                    "Translation complete."

                                voiceSpeechSender.send(
                                    rtc = rtc,
                                    customTrackId =
                                        customAudioTrackId,
                                    text = result
                                )
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { exception ->
                updateSession(expectedSession) {
                    if (
                        translationRequest == expectedRequest
                    ) {
                        translating = false
                        translationStatus =
                            "Translation failed: ${exception.message}"
                    }
                }
            }
    }

    private fun closeCall() {
        cancelPendingSpeech()
        session++

        val rtc = engine
        val trackId = customAudioTrackId

        engine = null
        customAudioTrackId = -1

        busy = false
        joined = false
        connected = false
        remoteUid = null
        sendingSpeech = false

        if (rtc != null) {
            try {
                rtc.stopPreview()
                rtc.leaveChannel()

                if (trackId != -1) {
                    rtc.destroyCustomAudioTrack(trackId)
                }
            } finally {
                RtcEngine.destroy()
            }
        }

        status = "Call ended"
    }

    override fun onStop() {
        speechRecognizer.cancel()

        if (engine != null) {
            closeCall()
        }

        super.onStop()
    }

    override fun onDestroy() {
        if (engine != null) {
            closeCall()
        }

        speechRecognizer.shutdown()
        voiceSpeechSender.shutdown()
        onDeviceTranslator.close()

        super.onDestroy()
    }

    @Composable
    private fun CallScreen(
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Ninshu",
                style = MaterialTheme.typography.headlineMedium
            )

            Text("Development call • $channelName")

            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it },
                label = { Text("Agora App ID") },
                enabled = !busy && !permissionPending,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Temporary RTC token") },
                visualTransformation =
                    PasswordVisualTransformation(),
                enabled = !busy && !permissionPending,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uidInput,
                onValueChange = { uidInput = it },
                label = { Text("UID: 1001 or 1002") },
                enabled = !busy && !permissionPending,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(status)

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { requestJoin() },
                    enabled = !busy && !permissionPending
                ) {
                    Text(
                        if (permissionPending) {
                            "Permissions…"
                        } else {
                            "Join"
                        }
                    )
                }

                OutlinedButton(
                    onClick = { closeCall() },
                    enabled = busy
                ) {
                    Text("Leave")
                }
            }

            Button(
                onClick = { sendVoiceTest() },
                enabled = canSendSpeech(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (sendingSpeech) {
                        "Sending…"
                    } else {
                        "Send voice test"
                    }
                )
            }

            Text(
                text =
                    "Original microphone audio is not transmitted. " +
                            "Only generated speech is sent.",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()

            Text(
                text = "On-device translation",
                style = MaterialTheme.typography.titleMedium
            )

            Text("$sourceLanguage → $targetLanguage")

            OutlinedButton(
                onClick = { prepareTranslation() },
                enabled = !preparingModel && !modelReady
            ) {
                Text(
                    when {
                        modelReady -> "Model ready"
                        preparingModel -> "Preparing…"
                        else -> "Prepare translation"
                    }
                )
            }

            Text(translationStatus)

            Text(
                text = "Speech input",
                style = MaterialTheme.typography.titleMedium
            )

            Text("Requested locale: $recognitionLanguage")
            Text(recognitionStatus)

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { startSpeechInput() },
                    enabled = canSendSpeech()
                ) {
                    Text("Start speaking")
                }

                OutlinedButton(
                    onClick = { speechRecognizer.stop() },
                    enabled = recognitionActive
                ) {
                    Text("Stop")
                }
            }

            OutlinedTextField(
                value = sourceText,
                onValueChange = {
                    sourceText = it
                    translatedText = ""
                },
                label = {
                    Text("Enter or recognize text ($sourceLanguage)")
                },
                enabled = !recognitionActive &&
                        !translating &&
                        !sendingSpeech,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { translateAndSend() },
                enabled = canSendSpeech() &&
                        modelReady &&
                        sourceText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        translating -> "Translating…"
                        sendingSpeech -> "Sending…"
                        else -> "Translate and Send"
                    }
                )
            }

            if (translatedText.isNotBlank()) {
                Text("Translated text: $translatedText")
            }

            HorizontalDivider()

            val rtc = engine

            if (rtc != null) {
                key(rtc) {
                    Text("You")

                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        factory = { context ->
                            SurfaceView(context).apply {
                                rtc.setupLocalVideo(
                                    VideoCanvas(
                                        this,
                                        VideoCanvas.RENDER_MODE_HIDDEN,
                                        0
                                    )
                                )

                                rtc.startPreview()
                            }
                        }
                    )

                    val participant = remoteUid

                    if (participant == null) {
                        Text("Waiting for the other participant…")
                    } else {
                        Text("Participant $participant")

                        key(participant) {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp),
                                factory = { context ->
                                    SurfaceView(context).apply {
                                        rtc.setupRemoteVideo(
                                            VideoCanvas(
                                                this,
                                                VideoCanvas.RENDER_MODE_HIDDEN,
                                                participant
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}