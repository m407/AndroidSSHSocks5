package com.example.data

import kotlinx.coroutines.flow.Flow

class SshProfileRepository(private val dao: SshProfileDao) {
    val allProfiles: Flow<List<SshProfile>> = dao.getAllProfiles()

    suspend fun getProfileById(id: Int): SshProfile? = dao.getProfileById(id)

    suspend fun insert(profile: SshProfile): Long = dao.insertProfile(profile)

    suspend fun update(profile: SshProfile) = dao.updateProfile(profile)

    suspend fun delete(profile: SshProfile) = dao.deleteProfile(profile)
}
