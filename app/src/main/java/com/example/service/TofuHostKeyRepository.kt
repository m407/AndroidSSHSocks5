package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

class TofuHostKeyRepository(context: Context) : HostKeyRepository {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun check(host: String, key: ByteArray): Int {
        val storedKey = preferences.getString(host.preferenceKey(), null)
        val incomingKey = key.toBase64()
        return when {
            storedKey == null -> {
                preferences.edit().putString(host.preferenceKey(), incomingKey).apply()
                Log.i(TAG, "Trusted first SSH host key for $host")
                HostKeyRepository.OK
            }
            storedKey == incomingKey -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        preferences.edit()
            .putString(hostkey.host.preferenceKey(), hostkey.key)
            .apply()
    }

    override fun remove(host: String, type: String?) {
        preferences.edit().remove(host.preferenceKey()).apply()
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        preferences.edit().remove(host.preferenceKey()).apply()
    }

    override fun getKnownHostsRepositoryID(): String = PREFERENCES_NAME

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    private fun String.preferenceKey(): String = "host_key:$this"

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        private const val PREFERENCES_NAME = "ssh_host_keys"
        private const val TAG = "TofuHostKeyRepository"
    }
}
