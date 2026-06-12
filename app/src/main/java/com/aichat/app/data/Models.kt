package com.aichat.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b"),
    CUSTOM("自訂端點", "", "");

    companion object {
        fun fromId(id: String?): Provider = entries.firstOrNull { it.name == id } ?: OPENROUTER
    }
}

enum class AppLanguage(val label: String) {
    TRADITIONAL_CHINESE("繁體中文"),
    SIMPLIFIED_CHINESE("简体中文");

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.name == id } ?: TRADITIONAL_CHINESE
    }
}

data class AppSettings(
    val provider: Provider = Provider.OPENROUTER,
    val customBaseUrl: String = "",
    val model: String = Provider.OPENROUTER.defaultModel,
    val darkTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.TRADITIONAL_CHINESE,
) {
    val resolvedBaseUrl: String
        get() = (if (provider == Provider.CUSTOM) customBaseUrl else provider.baseUrl).trimEnd('/')

    val usesUnsafeHttp: Boolean
        get() = resolvedBaseUrl.startsWith("http://", ignoreCase = true)
}

enum class ProfileType { CHARACTER, PERSONA }

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val type: ProfileType,
    val name: String,
    val summary: String = "",
    val personality: String = "",
    val background: String = "",
    val exampleDialogue: String = "",
    val greeting: String = "",
    val alternateGreetingsJson: String = "[]",
    val extraInstructions: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "world_sets")
data class WorldSetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scanDepth: Int = 10,
    val createdAt: Long,
    val updatedAt: Long,
    val overview: String = "",
)

data class WorldEntryCount(
    val worldSetId: String,
    val count: Int,
)

@Entity(
    tableName = "world_entries",
    foreignKeys = [
        ForeignKey(
            entity = WorldSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["worldSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("worldSetId")],
)
data class WorldEntryEntity(
    @PrimaryKey val id: String,
    val worldSetId: String,
    val title: String,
    val keywordsJson: String = "[]",
    val content: String,
    val alwaysInclude: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "conversation_world_sets",
    primaryKeys = ["conversationId", "worldSetId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorldSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["worldSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("worldSetId")],
)
data class ConversationWorldSetEntity(
    val conversationId: String,
    val worldSetId: String,
)

@Entity(
    tableName = "generation_contexts",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class GenerationContextEntity(
    @PrimaryKey val messageId: String,
    val activatedWorldEntriesJson: String = "[]",
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val contextStartAt: Long = 0,
    val characterId: String? = null,
    val personaId: String? = null,
    val summary: String = "",
    val summaryThroughAt: Long = 0,
    val backgroundImagePath: String = "",
    val messageBubbleOpacity: Float = 1f,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)
