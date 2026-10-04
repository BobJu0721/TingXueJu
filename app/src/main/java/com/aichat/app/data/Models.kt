package com.aichat.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Provider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1", "gpt-oss-120b"),
    AGNES("Agnes", "https://apihub.agnes-ai.com/v1", "agnes-2.0-flash"),
    CLOUDFLARE(
        "Cloudflare Workers AI",
        "https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1",
        "@cf/meta/llama-3.1-8b-instruct",
    ),
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

enum class ReasoningMode {
    AUTO,
    ON,
    OFF,
}

enum class ReplyLengthPreference { DEFAULT, SHORT, MEDIUM, DETAILED }

enum class TokenLimitField { AUTO, MAX_TOKENS, MAX_COMPLETION_TOKENS }

enum class MessageVersionSource { ORIGINAL, USER_EDIT, AI_EDIT, REGENERATED, CONTINUATION }

enum class MessageVersionStatus { COMPLETE, PARTIAL, INTERRUPTED, DRAFT }

data class AppSettings(
    val provider: Provider = Provider.OPENROUTER,
    val customBaseUrl: String = "",
    val cloudflareAccountId: String = "",
    val model: String = provider.defaultModel,
    val darkTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.TRADITIONAL_CHINESE,
) {
    val resolvedBaseUrl: String
        get() = when (provider) {
            Provider.CUSTOM -> customBaseUrl
            Provider.CLOUDFLARE -> provider.baseUrl.replace("{ACCOUNT_ID}", cloudflareAccountId.trim())
            else -> provider.baseUrl
        }.trimEnd('/')

    val resolvedModelsUrl: String
        get() = if (provider == Provider.CLOUDFLARE) {
            "${resolvedBaseUrl.removeSuffix("/v1")}/models/search?format=openrouter&per_page=100"
        } else {
            "$resolvedBaseUrl/models"
        }

    val usesUnsafeHttp: Boolean
        get() = resolvedBaseUrl.startsWith("http://", ignoreCase = true)
}

data class CustomEndpointPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
)

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
            entity = MessageVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("versionId")],
)
data class GenerationContextEntity(
    @PrimaryKey val versionId: String,
    val activatedWorldEntriesJson: String = "[]",
    val reasoningContent: String = "",
    val outputTokenCount: Long? = null,
    @ColumnInfo(defaultValue = "1") val tokenCountEstimated: Boolean = true,
    val generationElapsedMillis: Long? = null,
)

/**
 * 聊天室。
 *
 * `contextStartAt`、`summary`、`summaryThroughAt`、`contextStartOrder`、`summaryThroughOrder`
 * 是 v9 以前的全域狀態，v10 起改由 [ConversationBranchEntity] 擁有；這幾個欄位只保留原值，
 * 不再作為權威來源（v9→v10 遷移會把它們搬到初始路線）。
 */
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
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
    @ColumnInfo(defaultValue = "0") val contextStartOrder: Long = 0,
    @ColumnInfo(defaultValue = "0") val summaryThroughOrder: Long = 0,
    @ColumnInfo(defaultValue = "'DEFAULT'") val replyLengthPreference: ReplyLengthPreference = ReplyLengthPreference.DEFAULT,
    val maxOutputTokens: Int? = null,
    @ColumnInfo(defaultValue = "'AUTO'") val tokenLimitField: TokenLimitField = TokenLimitField.AUTO,
    @ColumnInfo(defaultValue = "0") val historyRevision: Long = 0,
    /** 目前顯示哪條路線；路線關聯才是顯示與生成的唯一依據。 */
    @ColumnInfo(defaultValue = "''") val activeBranchId: String = "",
)

/**
 * 一條路線：保存自己的後續對話、摘要、裁切起點與排除狀態。
 *
 * `forkMessageId` 是分岔所在的訊息（接續新訊息時為前文的最後一則）；`forkVersionId` 是這條路線
 * 建立時選定的版本，草稿回滾與中斷恢復靠它找回自己的候選分支。`sourceBranchId` 記錄來源路線。
 * 重建分支的相容路線會把 `legacyIncomplete` 設為 true，代表舊版沒有保存後續紀錄。
 */
@Entity(
    tableName = "conversation_branches",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "forkMessageId", "forkVersionId"]),
    ],
)
data class ConversationBranchEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sourceBranchId: String? = null,
    val forkMessageId: String? = null,
    val forkVersionId: String? = null,
    val createdAt: Long,
    val lastUsedAt: Long,
    val summary: String = "",
    @ColumnInfo(defaultValue = "0") val summaryThroughOrder: Long = 0,
    @ColumnInfo(defaultValue = "0") val contextStartOrder: Long = 0,
    @ColumnInfo(defaultValue = "0") val legacyIncomplete: Boolean = false,
    @ColumnInfo(defaultValue = "''") val sceneNote: String = "",
    @ColumnInfo(defaultValue = "0") val sceneNoteEnabled: Boolean = false,
)

data class SceneNote(val text: String = "", val enabled: Boolean = false)

fun ConversationBranchEntity.sceneNoteValue() = SceneNote(sceneNote, sceneNoteEnabled)

data class GenerationSnapshot(
    val conversation: ConversationEntity,
    val branch: ConversationBranchEntity,
    val messages: List<MessageEntity>,
)

/** 路線內依序排列的訊息及其選定版本。共同前文只共用內容，不複製正文。 */
@Entity(
    tableName = "branch_messages",
    primaryKeys = ["branchId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationBranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branchId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("branchId"),
        Index("messageId"),
        Index(value = ["branchId", "sortOrder"], unique = true),
    ],
)
data class BranchMessageEntity(
    val branchId: String,
    val messageId: String,
    val versionId: String,
    val sortOrder: Long,
    @ColumnInfo(defaultValue = "0") val excluded: Boolean = false,
)

/**
 * 目前路線的一次性快照。三個查詢在同一個交易內讀取，介面因此不會短暫看到
 * 「新版輸入＋舊版回答」這種混合狀態。
 */
data class RouteRows(
    val messages: List<RouteMessageRow>,
    val versions: List<MessageVersionEntity>,
    val contexts: List<GenerationContextEntity>,
)

/** 攤平後、可直接交給上層使用的路線快照。 */
data class RouteSnapshot(
    val messages: List<MessageEntity> = emptyList(),
    val versions: List<MessageVersionEntity> = emptyList(),
    val contexts: List<GenerationContextEntity> = emptyList(),
)
/**
 * 目前路線的一則訊息：訊息本身加上該路線選定的版本、順序與排除狀態。
 * 正文與思考內容仍共用 [MessageVersionEntity]，不隨路線複製。
 */
data class RouteMessageRow(
    @Embedded val message: MessageEntity,
    val routeVersionId: String,
    val routeSortOrder: Long,
    val routeExcluded: Boolean,
    val routeContent: String,
    val routeAuthorName: String? = null,
    val routeAuthorCharacterId: String? = null,
) {    /** 攤平成上層習慣的 [MessageEntity]：路線選定值覆蓋掉訊息本身的 legacy 欄位。 */
    fun resolved(): MessageEntity = message.copy(
        content = routeContent,
        currentVersionId = routeVersionId,
        sortOrder = routeSortOrder,
        excluded = routeExcluded,
        authorName = routeAuthorName,
    )
}

/**
 * 可提供給模型的內容資格判斷，聊天、摘要、世界掃描與代擬共用。
 *
 * 私人註記在格式轉換之前就排除，不是靠「加上請忽略的標籤」。
 */
val MessageEntity.isModelVisible: Boolean
    get() = !deleted && !excluded && content.isNotBlank() && kind != MessageKind.PRIVATE_NOTE

/** 算故事內容、會進摘要與世界掃描的訊息（私人註記不算）。 */
val MessageEntity.isStoryContent: Boolean
    get() = isModelVisible

/**
 * 送給模型的內容。旁白與手寫角色台詞是作者補寫的故事紀錄，以 `user` 身分加上明確標記。
 */
fun MessageEntity.toPromptContent(): String = when (kind) {
    MessageKind.NARRATION -> "【旁白】\n$content"
    MessageKind.AUTHORED_CHARACTER -> "【角色台詞：${authorName.orEmpty()}】\n$content"
    else -> content
}

/** 送給模型時使用的 API role；手寫內容一律以使用者身分送出。 */
fun MessageEntity.promptRole(): String = if (kind.isAuthored) "user" else role

/**
 * 可以「從這裡讓 AI 接話」的訊息：真正的使用者訊息，或作者手寫的故事內容
 * （旁白、指定角色台詞）。私人註記永遠不行。
 */
val MessageEntity.acceptsAiReply: Boolean
    get() = kind.isAuthored || (kind == MessageKind.CHAT && role == "user")

/** 可以被「生成另一版」或「續寫」的模型回覆；手寫角色台詞不算。 */
val MessageEntity.canRegenerate: Boolean get() = isAiReply

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
    indices = [Index("conversationId"), Index(value = ["conversationId", "sortOrder"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val currentVersionId: String = "",
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    @ColumnInfo(defaultValue = "0") val excluded: Boolean = false,
    /**
     * 使用者明確刪除後留下的結構標記。正文、思考與生成資料都已清空，只是為了讓其他路線
     * 還找得到分岔入口。刪除標記不送給 AI，也不參與世界觸發或摘要。
     */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** 訊息種類。建立後不提供轉換，尤其私人註記不能直接變成模型可見的訊息。 */
    @ColumnInfo(defaultValue = "'CHAT'") val kind: MessageKind = MessageKind.CHAT,
    /**
     * 目前選定版本的手寫角色署名。和 [content]／[currentVersionId] 一樣只是「目前路線選定值」
     * 的鏡像，權威來源在 [MessageVersionEntity.authorName]，所以改名前後的舊署名都留得住。
     */
    val authorName: String? = null,
) {
    /** 是不是由模型生成、可以再被覆寫或續寫的一般 AI 回覆。 */
    val isAiReply: Boolean get() = kind == MessageKind.CHAT && role == "assistant"
}

/**
 * 訊息種類。不能用 `role == "assistant"` 判斷誰是 AI 生成的回覆：旁白與手寫角色台詞也帶著
 * 故事內容，但它們是作者寫的。
 */
enum class MessageKind {
    CHAT,
    NARRATION,
    AUTHORED_CHARACTER,
    PRIVATE_NOTE,
    ;

    /** 作者手寫、算故事內容但不佔角色發言名額的種類。 */
    val isAuthored: Boolean get() = this == NARRATION || this == AUTHORED_CHARACTER
}

@Entity(
    tableName = "message_versions",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId"), Index(value = ["messageId", "versionNumber"], unique = true)],
)
data class MessageVersionEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val versionNumber: Int,
    val content: String,
    val createdAt: Long,
    val source: MessageVersionSource = MessageVersionSource.ORIGINAL,
    val baseVersionId: String? = null,
    val status: MessageVersionStatus = MessageVersionStatus.COMPLETE,
    /** 手寫角色台詞的署名快照與可選角色 ID；放在版本上，改名前的舊署名才留得住。 */
    val authorName: String? = null,
    val authorCharacterId: String? = null,
)
