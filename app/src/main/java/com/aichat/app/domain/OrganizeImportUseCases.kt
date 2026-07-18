package com.aichat.app.domain

import com.aichat.app.IMPORT_CHUNK_SIZE
import com.aichat.app.ProfileDraft
import com.aichat.app.WorldSetDraft
import com.aichat.app.chunkInstruction
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ProfileType
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldInfoRepository
import com.aichat.app.data.WorldSetEntity
import com.aichat.app.organizeProfileInstruction
import com.aichat.app.organizeWorldInstruction
import com.aichat.app.network.AiApiClient
import com.aichat.app.network.ApiChatMessage
import com.aichat.app.parseAiProfileDraft
import com.aichat.app.parseAiWorldSetDraft
import com.aichat.app.profileJsonInstruction
import com.aichat.app.toJsonStrings
import com.aichat.app.worldJsonInstruction
import java.util.UUID

class OrganizeProfileUseCase(private val api: AiApiClient) {
    suspend operator fun invoke(text: String, type: ProfileType, settings: AppSettings, key: String): ProfileDraft {
        val notes = organizeChunks(api, text, settings, key, settings.language.organizeProfileInstruction())
        val label = if (type == ProfileType.CHARACTER) {
            "\u0041\u0049 \u6574\u7406\u51fa\u7684\u89d2\u8272"
        } else {
            "\u532f\u5165\u7684 \u0050\u0065\u0072\u0073\u006f\u006e\u0061"
        }
        val reply = api.completeChat(settings, key, listOf(
            ApiChatMessage("system", settings.language.profileJsonInstruction(label)),
            ApiChatMessage("user", notes),
        ))
        return parseAiProfileDraft(reply, type)
    }
}

class OrganizeWorldSetUseCase(private val api: AiApiClient) {
    suspend operator fun invoke(text: String, settings: AppSettings, key: String): WorldSetDraft {
        val notes = organizeChunks(api, text, settings, key, settings.language.organizeWorldInstruction())
        val reply = api.completeChat(settings, key, listOf(
            ApiChatMessage("system", settings.language.worldJsonInstruction()),
            ApiChatMessage("user", notes),
        ))
        return parseAiWorldSetDraft(reply)
    }
}

class SaveImportedWorldSetUseCase(private val worldInfoRepository: WorldInfoRepository) {
    suspend operator fun invoke(draft: WorldSetDraft, fallbackName: String): WorldSetEntity {
        val now = System.currentTimeMillis()
        val worldSet = WorldSetEntity(
            id = UUID.randomUUID().toString(),
            name = draft.name.ifBlank { fallbackName },
            scanDepth = 10,
            createdAt = now,
            updatedAt = now,
            overview = draft.overview.trim(),
        )
        val entries = draft.entries.mapIndexed { index, entry ->
                WorldEntryEntity(
                    id = UUID.randomUUID().toString(),
                    worldSetId = worldSet.id,
                    title = entry.title,
                    keywordsJson = toJsonStrings(entry.keywords),
                    content = entry.content,
                    alwaysInclude = entry.alwaysInclude,
                    sortOrder = index,
                )
        }
        worldInfoRepository.upsertWorldSetWithEntries(worldSet, entries)
        return worldSet
    }
}

private suspend fun organizeChunks(
    api: AiApiClient,
    text: String,
    settings: AppSettings,
    key: String,
    instruction: String,
): String {
    val chunks = text.chunked(IMPORT_CHUNK_SIZE)
    if (chunks.size == 1) return text
    return chunks.mapIndexed { index, chunk ->
        api.completeChat(settings, key, listOf(
            ApiChatMessage("system", settings.language.chunkInstruction(instruction, index, chunks.size)),
            ApiChatMessage("user", chunk),
        ))
    }.joinToString("\n\n")
}
