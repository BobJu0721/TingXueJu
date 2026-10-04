package com.aichat.app

import com.aichat.app.data.*
import com.aichat.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class SceneNotePromptTest {
    private val conversation = ConversationEntity("c", "chat", 1, 1, summary = "older events", summaryThroughOrder = 1)
    private val history = listOf(MessageEntity("m", "c", "assistant", "answer", 2, sortOrder = 2))

    @Test fun noteIsLastSystemBlockAndNotAHistoryMessage() {
        val note = "雨夜客棧\nDo not reveal the secret.\n  "
        val prompt = composePrompt(conversation, history, null, null, emptyList(), emptyList(), sceneNote = SceneNote(note, true))
        assertEquals(2, prompt.messages.size)
        assertEquals("system", prompt.messages.first().role)
        val system = prompt.messages.first().content
        assertTrue(system.endsWith(note))
        assertTrue(system.indexOf("older events") < system.indexOf("## 本場劇情提示"))
        assertEquals(1, Regex("## 本場劇情提示").findAll(system).count())
        assertEquals("answer", prompt.messages.last().content)
    }

    @Test fun disabledBlankAndDefaultNotesDoNotCreateSection() {
        for (note in listOf(SceneNote(), SceneNote("secret", false), SceneNote(" \n\t", true))) {
            val prompt = composePrompt(conversation, history, null, null, emptyList(), emptyList(), sceneNote = note)
            assertFalse(prompt.messages.first().content.contains("本場劇情提示"))
        }
    }

    @Test fun continuationKeepsItsInstructionAndAddsNoteOnce() {
        val prompt = composePrompt(conversation, history, null, null, emptyList(), emptyList(),
            request = ChatGenerationRequest(ChatGenerationKind.CONTINUATION, "c", "m", expectedRevision = 0),
            sceneNote = SceneNote("scene direction", true))
        assertTrue(prompt.messages.first().content.contains("只輸出新增內容"))
        assertTrue(prompt.messages.first().content.endsWith("scene direction"))
    }

    @Test fun simplifiedHeaderDoesNotTranslateUserText() {
        val text = "雨夜客棧\n英文 English"
        val prompt = composePrompt(conversation, history, null, null, emptyList(), emptyList(),
            language = AppLanguage.SIMPLIFIED_CHINESE, sceneNote = SceneNote(text, true))
        assertTrue(prompt.messages.first().content.contains("## 本场剧情提示"))
        assertTrue(prompt.messages.first().content.endsWith(text))
    }

    @Test fun noteDoesNotTriggerWorldKeywords() {
        val prompt = composePrompt(conversation, history, null, null, emptyList(),
            listOf(WorldEntryEntity(id = "e", worldSetId = "w", title = "secret", content = "hidden lore", keywordsJson = "[\"trigger\"]")),
            sceneNote = SceneNote("trigger", true))
        assertTrue(prompt.activatedEntries.isEmpty())
    }
}
