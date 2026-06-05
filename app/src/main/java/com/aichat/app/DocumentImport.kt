package com.aichat.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.aichat.app.data.ProfileType
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

private const val MAX_FILE_BYTES = 2_000_000
const val IMPORT_CHUNK_SIZE = 12_000
const val MAX_IMPORT_TEXT = 200_000

data class ImportedDocument(
    val name: String,
    val text: String,
)

data class ProfileDraft(
    val id: String? = null,
    val type: ProfileType,
    val name: String = "",
    val summary: String = "",
    val personality: String = "",
    val background: String = "",
    val exampleDialogue: String = "",
    val greeting: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val extraInstructions: String = "",
)

data class WorldEntryDraft(
    val title: String = "",
    val keywords: List<String> = emptyList(),
    val content: String = "",
    val alwaysInclude: Boolean = false,
)

data class WorldSetDraft(
    val name: String = "",
    val overview: String = "",
    val entries: List<WorldEntryDraft> = emptyList(),
)

fun readImportedDocument(context: Context, uri: Uri): ImportedDocument {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "匯入文件"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_FILE_BYTES) { "文件過大，請使用小於 2 MB 的檔案。" }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("無法開啟文件。")
    val text = when {
        name.endsWith(".docx", ignoreCase = true) -> extractDocxText(bytes)
        name.endsWith(".txt", ignoreCase = true) || name.endsWith(".json", ignoreCase = true) ->
            bytes.toString(Charsets.UTF_8)
        else -> error("只支援 TXT、JSON 與 DOCX 文件。")
    }.trim()
    require(text.isNotBlank()) { "文件沒有可用文字。" }
    require(text.length <= MAX_IMPORT_TEXT) { "文件文字過長，請縮短至 20 萬字元內。" }
    return ImportedDocument(name, text)
}

private fun extractDocxText(bytes: ByteArray): String {
    val documentXml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }
            .firstOrNull { it.name == "word/document.xml" }
            ?.let { zip.readBytes() }
    } ?: error("DOCX 文件缺少正文。")
    val parser = Xml.newPullParser().apply {
        setInput(ByteArrayInputStream(documentXml), "UTF-8")
    }
    return buildString {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "t") append(parser.nextText())
            if (event == XmlPullParser.END_TAG && (parser.name == "p" || parser.name == "tr")) appendLine()
            event = parser.next()
        }
    }
}

fun profileDraftFromOwnJson(text: String, type: ProfileType): ProfileDraft? = runCatching {
    val json = JSONObject(text)
    if (!json.has("name") || !listOf("summary", "personality", "background", "exampleDialogue", "greeting")
            .any(json::has)
    ) return@runCatching null
    ProfileDraft(
        type = type,
        name = json.optString("name"),
        summary = json.optString("summary"),
        personality = json.optString("personality"),
        background = json.optString("background"),
        exampleDialogue = json.optString("exampleDialogue"),
        greeting = json.optString("greeting"),
        alternateGreetings = json.optJSONArray("alternateGreetings").strings(),
        extraInstructions = json.optString("extraInstructions"),
    )
}.getOrNull()

fun parseAiProfileDraft(text: String, type: ProfileType): ProfileDraft {
    val json = JSONObject(extractJsonObject(text))
    return ProfileDraft(
        type = type,
        name = json.optString("name"),
        summary = json.optString("summary"),
        personality = json.optString("personality"),
        background = json.optString("background"),
        exampleDialogue = json.optString("exampleDialogue"),
        greeting = json.optString("greeting"),
        alternateGreetings = json.optJSONArray("alternateGreetings").strings(),
        extraInstructions = json.optString("extraInstructions"),
    )
}

fun parseAiWorldSetDraft(text: String): WorldSetDraft {
    val json = JSONObject(extractJsonObject(text))
    val entries = json.optJSONArray("entries") ?: JSONArray()
    return WorldSetDraft(
        name = json.optString("name", "匯入的世界設定"),
        overview = json.optString("overview"),
        entries = buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                add(
                    WorldEntryDraft(
                        title = entry.optString("title"),
                        keywords = entry.optJSONArray("keywords").strings(),
                        content = entry.optString("content"),
                        alwaysInclude = entry.optBoolean("alwaysInclude"),
                    ),
                )
            }
        }.filter { it.title.isNotBlank() && it.content.isNotBlank() },
    )
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
    }
}

private fun extractJsonObject(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    require(start >= 0 && end > start) { "AI 沒有回傳可讀取的 JSON。" }
    return text.substring(start, end + 1)
}
