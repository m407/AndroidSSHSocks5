package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.SshProfile
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SshProxyService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sshSession: Session? = null
    private var localSocks5Proxy: LocalSocks5Proxy? = null

    companion object {
        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val EXTRA_PROFILE_ID = "com.example.extra.PROFILE_ID"

        private const val NOTIFICATION_ID = 4829
        private const val CHANNEL_ID = "SshProxyChannel"

        private val _status = MutableStateFlow(ProxyStatus.DISCONNECTED)
        val status: StateFlow<ProxyStatus> = _status

        private val _activeProfile = MutableStateFlow<SshProfile?>(null)
        val activeProfile: StateFlow<SshProfile?> = _activeProfile

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        fun startProxy(context: Context, profileId: Int) {
            val intent = Intent(context, SshProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopProxy(context: Context) {
            val intent = Intent(context, SshProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val profileId = intent.getIntExtra(EXTRA_PROFILE_ID, -1)
            startForeground(NOTIFICATION_ID, createNotification("SOCKS5 over SSH", "Starting proxy..."))
            if (profileId != -1) {
                launchSshProxy(profileId)
            } else {
                _status.value = ProxyStatus.ERROR
                _lastError.value = "Invalid profile ID"
                stopSelf()
            }
        } else if (action == ACTION_STOP) {
            stopProxyService()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOCKS5 Over SSH Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows SOCKS5 proxy server status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            // Use standard Android icon because we guaranteed it exists
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun launchSshProxy(profileId: Int) {
        serviceScope.launch {
            _status.value = ProxyStatus.CONNECTING
            _lastError.value = null

            // Retrieve profile from Database
            val db = AppDatabase.getDatabase(applicationContext)
            val profile = db.sshProfileDao().getProfileById(profileId)
            if (profile == null) {
                _status.value = ProxyStatus.ERROR
                _lastError.value = "SOCKS5 profile not found in DB."
                updateNotification("Connection Failed", "Profile not found")
                stopSelf()
                return@launch
            }

            _activeProfile.value = profile
            updateNotification("Connecting to SSH...", "${profile.host}:${profile.port}")

            try {
                val jsch = JSch()

                if (profile.authType == "KEY" && profile.privateKey.isNotEmpty()) {
                    val privateKeyBytes = profile.privateKey.toByteArray(Charsets.UTF_8)
                    val passphraseBytes = if (profile.passphrase.isNotEmpty()) {
                        profile.passphrase.toByteArray(Charsets.UTF_8)
                    } else {
                        null
                    }
                    jsch.addIdentity("profile_${profile.id}", privateKeyBytes, null, passphraseBytes)
                }

                val session = jsch.getSession(profile.username, profile.host, profile.port)
                sshSession = session

                if (profile.authType == "PASSWORD" && profile.password.isNotEmpty()) {
                    session.setPassword(profile.password)
                }

                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")

                // Add keepalive to make connection robust
                session.setServerAliveInterval(30000) // 30 seconds
                session.setServerAliveCountMax(3)

                session.connect(15000) // Timeout 15s

                // Bind to SOCKS5 dynamic port forwarding using custom localized SOCKS5 proxy
                localSocks5Proxy = LocalSocks5Proxy(profile.socksPort) { sshSession }.apply {
                    start()
                }

                _status.value = ProxyStatus.CONNECTED
                updateNotification(
                    "SOCKS5 Proxy Active",
                    "Local Port: ${profile.socksPort} | Tunnel: ${profile.host}"
                )

                // Wait or monitor the session.
                while (isActive && session.isConnected) {
                    delay(2000)
                }

                if (!session.isConnected && _status.value == ProxyStatus.CONNECTED) {
                    throw Exception("SSH Connection lost unexpectedly")
                }

            } catch (e: Exception) {
                Log.e("SshProxyService", "Error during SSH proxy execution", e)
                _status.value = ProxyStatus.ERROR
                _lastError.value = e.localizedMessage ?: e.message ?: "Unknown SSH connection error"
                updateNotification("Connection Error", _lastError.value ?: "Unknown error")
                stopSelf()
            }
        }
    }

    private fun updateNotification(title: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun stopProxyService() {
        _status.value = ProxyStatus.DISCONNECTED
        _activeProfile.value = null
        serviceScope.coroutineContext.cancelChildren()

        try {
            localSocks5Proxy?.stop()
        } catch (e: Exception) {
            Log.e("SshProxyService", "Error stopping local SOCKS5 proxy", e)
        }
        localSocks5Proxy = null

        try {
            sshSession?.disconnect()
        } catch (e: Exception) {
            Log.e("SshProxyService", "Error disconnecting session", e)
        }
        sshSession = null
        stopSelf()
    }

    override fun onDestroy() {
        stopProxyService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
