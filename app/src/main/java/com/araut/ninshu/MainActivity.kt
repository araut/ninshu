package com.araut.ninshu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.araut.ninshu.ui.theme.NinshuTheme

import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
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
    private var microphoneMuted by mutableStateOf(false)

    private var engine by mutableStateOf<RtcEngine?>(null)
    private var session = 0

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

        setContent {
            NinshuTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    CallScreen(
                        modifier = Modifier.padding(padding)
                    )
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

    // SDK events may arrive off the main thread.
    // Ignore events from a call that has already been closed.
    private fun updateSession(
        expectedSession: Int,
        update: () -> Unit
    ) {
        runOnUiThread {
            if (session == expectedSession) update()
        }
    }

    private fun checkResult(operation: String, result: Int) {
        check(result == 0) { "$operation failed: $result" }
    }

    private fun joinCall() {
        if (busy || isFinishing || isDestroyed) return

        val id = appId.trim()
        val rtcToken = token.trim()
        val uid = uidInput.trim().toIntOrNull()

        if (id.length != 32 || rtcToken.isBlank() ||
            uid == null || uid <= 0
        ) {
            status = "Enter a valid App ID, token, and positive numeric UID."
            return
        }

        busy = true
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

                override fun onUserJoined(uid: Int, elapsed: Int) {
                    updateSession(currentSession) {
                        remoteUid = uid
                        status = "Participant $uid joined"
                    }
                }

                override fun onUserOffline(uid: Int, reason: Int) {
                    updateSession(currentSession) {
                        if (remoteUid == uid) remoteUid = null
                        status = "Participant $uid left"
                    }
                }

                override fun onConnectionStateChanged(
                    state: Int,
                    reason: Int
                ) {
                    updateSession(currentSession) {
                        status = when (state) {
                            Constants.CONNECTION_STATE_CONNECTED ->
                                "Connected to $channelName"

                            Constants.CONNECTION_STATE_RECONNECTING ->
                                "Reconnecting…"

                            Constants.CONNECTION_STATE_FAILED ->
                                "Connection failed ($reason). Leave and retry."

                            else ->
                                "Connection state: $state, reason: $reason"
                        }
                    }
                }

                override fun onTokenPrivilegeWillExpire(token: String?) {
                    updateSession(currentSession) {
                        status = "Token expiring. Leave and enter a new token."
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

            checkResult("enableVideo", rtc.enableVideo())

            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                publishCameraTrack = true
                publishMicrophoneTrack = true
                autoSubscribeAudio = true
                autoSubscribeVideo = true
            }

            checkResult(
                "joinChannel",
                rtc.joinChannel(rtcToken, channelName, uid, options)
            )
        } catch (exception: Exception) {
            closeCall()
            status = exception.message ?: "Unable to start call."
        }
    }

    private fun closeCall() {
        // Invalidate pending callbacks before destroying the engine.
        session++

        val rtc = engine
        engine = null
        busy = false
        joined = false
        remoteUid = null
        microphoneMuted = false

        if (rtc != null) {
            rtc.stopPreview()
            rtc.leaveChannel()
            RtcEngine.destroy()
        }

        status = "Call ended"
    }

    override fun onStop() {
        // Foreground-only prototype: release camera/mic on backgrounding.
        if (engine != null) closeCall()
        super.onStop()
    }

    override fun onDestroy() {
        if (engine != null) closeCall()
        super.onDestroy()
    }

    @Composable
    private fun CallScreen(modifier: Modifier = Modifier) {
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
                visualTransformation = PasswordVisualTransformation(),
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { requestJoin() },
                    enabled = !busy && !permissionPending
                ) {
                    Text(if (permissionPending) "Permissions…" else "Join")
                }

                OutlinedButton(
                    onClick = { closeCall() },
                    enabled = busy
                ) {
                    Text("Leave")
                }

                OutlinedButton(
                    enabled = joined,
                    onClick = {
                        val muted = !microphoneMuted
                        val result = engine?.muteLocalAudioStream(muted)
                        if (result == 0) {
                            microphoneMuted = muted
                        } else {
                            status = "Unable to change microphone: $result"
                        }
                    }
                ) {
                    Text(if (microphoneMuted) "Unmute" else "Mute")
                }
            }

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