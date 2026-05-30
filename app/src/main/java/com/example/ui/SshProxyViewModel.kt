package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.SshProfile
import com.example.data.SshProfileRepository
import com.example.service.ProxyStatus
import com.example.service.SshProxyService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SshProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SshProfileRepository
    val profiles: StateFlow<List<SshProfile>>

    val proxyStatus: StateFlow<ProxyStatus> = SshProxyService.status
    val activeProfile: StateFlow<SshProfile?> = SshProxyService.activeProfile
    val lastError: StateFlow<String?> = SshProxyService.lastError

    init {
        val db = AppDatabase.getDatabase(application)
        repository = SshProfileRepository(db.sshProfileDao())
        profiles = repository.allProfiles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun saveProfile(profile: SshProfile) {
        viewModelScope.launch {
            if (profile.id == 0) {
                repository.insert(profile)
            } else {
                repository.update(profile)
            }
        }
    }

    fun deleteProfile(profile: SshProfile) {
        viewModelScope.launch {
            if (activeProfile.value?.id == profile.id) {
                SshProxyService.stopProxy(getApplication())
            }
            repository.delete(profile)
        }
    }

    fun toggleProxy(profile: SshProfile) {
        val currentStatus = proxyStatus.value
        val currentActive = activeProfile.value

        if ((currentStatus == ProxyStatus.CONNECTED || currentStatus == ProxyStatus.CONNECTING) && currentActive?.id == profile.id) {
            SshProxyService.stopProxy(getApplication())
        } else {
            if (currentStatus != ProxyStatus.DISCONNECTED) {
                SshProxyService.stopProxy(getApplication())
            }
            SshProxyService.startProxy(getApplication(), profile.id)
        }
    }
}
