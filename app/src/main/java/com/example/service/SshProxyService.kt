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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SshProxyService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectionMutex = Mutex()
    private var connectionJob: Job? = null
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
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getIntExtra(EXTRA_PROFILE_ID, -1)
                startForeground(NOTIFICATION_ID, createNotification("SOCKS5 over SSH", "Starting proxy..."))
                if (profileId == -1) {
                    _status.value = ProxyStatus.ERROR
                    _lastError.value = "Invalid profile ID"
                    stopSelf()
                } else {
                    startConnection(profileId)
                }
            }
            ACTION_STOP -> stopConnection()
        }
        return START_NOT_STICKY
    }

    private fun startConnection(profileId: Int) {
        val previousJob = connectionJob
        connectionJob = serviceScope.launch {
            previousJob?.cancelAndJoin()
            connectionMutex.withLock {
                cleanupConnection()
                runSshProxy(profileId)
            }
        }
    }

    private fun stopConnection() {
        val jobToStop = connectionJob
        serviceScope.launch {
            jobToStop?.cancelAndJoin()
            connectionMutex.withLock {
                cleanupConnection()
                _status.value = ProxyStatus.DISCONNECTED
                _activeProfile.value = null
                updateNotification("SOCKS5 Proxy Stopped", "Proxy disconnected")
                stopSelf()
            }
        }
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
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private suspend fun runSshProxy(profileId: Int) {
        _status.value = ProxyStatus.CONNECTING
        _lastError.value = null

        val profile = AppDatabase.getDatabase(applicationContext)
            .sshProfileDao()
            .getProfileById(profileId)

        if (profile == null) {
            _status.value = ProxyStatus.ERROR
            _lastError.value = "SOCKS5 profile not found in DB."
            updateNotification("Connection Failed", "Profile not found")
            stopSelf()
            return
        }

        _activeProfile.value = profile
        updateNotification("Connecting to SSH...", "${profile.host}:${profile.port}")

        try {
            validateProfile(profile)

            val jsch = JSch().apply {
                hostKeyRepository = TofuHostKeyRepository(applicationContext)
            }
            if (profile.authType == "KEY") {
                val privateKeyBytes = profile.privateKey.toByteArray(Charsets.UTF_8)
                val passphraseBytes = profile.passphrase
                    .takeIf { it.isNotEmpty() }
                    ?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("profile_${profile.id}", privateKeyBytes, null, passphraseBytes)
            }

            val session = jsch.getSession(profile.username, profile.host, profile.port)
            sshSession = session

            if (profile.authType == "PASSWORD") {
                session.setPassword(profile.password)
            }

            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.setServerAliveInterval(30_000)
            session.setServerAliveCountMax(3)
            session.connect(15_000)

            localSocks5Proxy = LocalSocks5Proxy(profile.socksPort) { sshSession }.apply {
                start()
            }

            _status.value = ProxyStatus.CONNECTED
            updateNotification(
                "SOCKS5 Proxy Active",
                "Local Port: ${profile.socksPort} | Tunnel: ${profile.host}"
            )

            while (currentCoroutineContext().isActive && session.isConnected) {
                delay(2_000)
            }

            if (!session.isConnected && _status.value == ProxyStatus.CONNECTED) {
                throw IllegalStateException("SSH connection lost unexpectedly")
            }
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) return
            Log.e("SshProxyService", "Error during SSH proxy execution", e)
            cleanupConnection()
            _status.value = ProxyStatus.ERROR
            _lastError.value = e.localizedMessage ?: e.message ?: "Unknown SSH connection error"
            updateNotification("Connection Error", _lastError.value ?: "Unknown error")
            stopSelf()
        }
    }

    private fun validateProfile(profile: SshProfile) {
        require(profile.host.isNotBlank()) { "SSH host is required" }
        require(profile.username.isNotBlank()) { "SSH username is required" }
        require(profile.port in 1..65535) { "SSH port must be between 1 and 65535" }
        require(profile.socksPort in 1024..65535) { "SOCKS5 port must be between 1024 and 65535" }
        when (profile.authType) {
            "PASSWORD" -> require(profile.password.isNotEmpty()) { "SSH password is required" }
            "KEY" -> require(profile.privateKey.isNotBlank()) { "SSH private key is required" }
            else -> error("Unsupported authentication type: ${profile.authType}")
        }
    }

    private fun updateNotification(title: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun cleanupConnection() {
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
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        cleanupConnection()
        _status.value = ProxyStatus.DISCONNECTED
        _activeProfile.value = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
