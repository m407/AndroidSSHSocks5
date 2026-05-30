package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SshProfileDao {
    @Query("SELECT * FROM ssh_profiles ORDER BY id DESC")
    fun getAllProfiles(): Flow<List<SshProfile>>

    @Query("SELECT * FROM ssh_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Int): SshProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: SshProfile): Long

    @Update
    suspend fun updateProfile(profile: SshProfile)

    @Delete
    suspend fun deleteProfile(profile: SshProfile)
}
