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

    private var status by mutableStateOf("Ready")
    private var busy by mutableStateOf(false)
    private var permissionPending by mutableStateOf(false)
    private var joined by mutableStateOf(false)
    private var remoteUid by mutableStateOf<Int?>(null)
    private var sendingSpeech by mutableStateOf(false)

    private var engine by mutableStateOf<RtcEngine?>(null)
    private var customAudioTrackId = -1
    private var session = 0

    private lateinit var voiceSpeechSender: VoiceSpeechSender

    private val channelName = "ninshu-dev"

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
            status = "Camera and microphone permissions are required."
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

        setContent {
            NinshuTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { padding ->
                    CallScreen(
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
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

    private fun checkResult(operation: String, result: Int) {
        check(result == 0) {
            "$operation failed: $result"
        }
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
                            voiceSpeechSender.cancel()
                        }

                        status = "Participant $uid left"
                    }
                }

                override fun onConnectionStateChanged(
                    state: Int,
                    reason: Int
                ) {
                    updateSession(currentSession) {
                        when (state) {
                            Constants.CONNECTION_STATE_CONNECTED -> {
                                status = "Connected to $channelName"
                            }

                            Constants.CONNECTION_STATE_RECONNECTING -> {
                                voiceSpeechSender.cancel()
                                status = "Reconnecting…"
                            }

                            Constants.CONNECTION_STATE_FAILED -> {
                                voiceSpeechSender.cancel()
                                joined = false
                                status =
                                    "Connection failed ($reason). " +
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
                        status = "Agora error: $err. Leave and retry."
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

            // This milestone publishes generated speech only.
            // Stop capturing the physical microphone.
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
            status = exception.message ?: "Unable to start call."
        }
    }

    private fun sendVoiceTest() {
        val rtc = engine ?: return

        if (
            !joined ||
            remoteUid == null ||
            sendingSpeech ||
            customAudioTrackId == -1
        ) {
            return
        }

        voiceSpeechSender.send(
            rtc = rtc,
            customTrackId = customAudioTrackId,
            text = BuildConfig.SPEECH_TEST_TEXT
        )
    }

    private fun closeCall() {
        // Cancel pending synthesis and audio pushes before
        // releasing the Agora engine.
        voiceSpeechSender.cancel()

        // Ignore callbacks queued by the previous session.
        session++

        val rtc = engine
        val trackId = customAudioTrackId

        engine = null
        customAudioTrackId = -1

        busy = false
        joined = false
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
        // Foreground-only prototype.
        // Release camera and audio resources when backgrounded.
        if (engine != null) {
            closeCall()
        }

        super.onStop()
    }

    override fun onDestroy() {
        if (engine != null) {
            closeCall()
        }

        voiceSpeechSender.shutdown()
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
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

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                enabled = joined &&
                        remoteUid != null &&
                        !sendingSpeech,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (sendingSpeech) {
                        "Sending…"
                    } else {
                        "Send Voice test"
                    }
                )
            }

            Text(
                text =
                    "Test mode: microphone audio is not transmitted. " +
                            "Generated speech is sent to the other phone.",
                style = MaterialTheme.typography.bodySmall
            )

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