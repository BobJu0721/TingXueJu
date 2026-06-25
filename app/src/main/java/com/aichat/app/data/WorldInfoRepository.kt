package com.aichat.app.data

import kotlinx.coroutines.flow.Flow

class WorldInfoRepository(private val dao: ChatDao) {
    fun observeWorldSets(): Flow<List<WorldSetEntity>> = dao.observeWorldSets()
    fun observeWorldEntries(worldSetId: String): Flow<List<WorldEntryEntity>> = dao.observeWorldEntries(worldSetId)
    fun observeWorldEntryCounts(): Flow<List<WorldEntryCount>> = dao.observeWorldEntryCounts()
    fun observeConversationWorldSetIds(conversationId: String): Flow<List<String>> =
        dao.observeConversationWorldSetIds(conversationId)

    suspend fun getWorldSet(id: String): WorldSetEntity? = dao.getWorldSet(id)
    suspend fun getWorldSets(ids: List<String>): List<WorldSetEntity> = dao.getWorldSets(ids)
    suspend fun upsertWorldSet(worldSet: WorldSetEntity) = dao.upsertWorldSet(worldSet)
    suspend fun deleteWorldSet(worldSet: WorldSetEntity) = dao.deleteWorldSet(worldSet)

    suspend fun getWorldEntries(worldSetIds: List<String>): List<WorldEntryEntity> = dao.getWorldEntries(worldSetIds)
    suspend fun upsertWorldEntry(entry: WorldEntryEntity) = dao.upsertWorldEntry(entry)
    suspend fun deleteWorldEntry(entry: WorldEntryEntity) = dao.deleteWorldEntry(entry)
}
