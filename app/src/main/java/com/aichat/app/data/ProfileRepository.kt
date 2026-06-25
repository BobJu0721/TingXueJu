package com.aichat.app.data

import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val dao: ChatDao) {
    fun observeProfiles(type: ProfileType): Flow<List<ProfileEntity>> = dao.observeProfiles(type)
    suspend fun getProfile(id: String?): ProfileEntity? = dao.getProfile(id)
    suspend fun upsertProfile(profile: ProfileEntity) = dao.upsertProfile(profile)
    suspend fun deleteProfile(profile: ProfileEntity) = dao.deleteProfile(profile)
}
