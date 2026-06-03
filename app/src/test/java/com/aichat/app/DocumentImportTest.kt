package com.aichat.app

import com.aichat.app.data.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImportTest {
    @Test
    fun ownJsonFillsProfileWithoutAi() {
        val draft = profileDraftFromOwnJson(
            """{"name":"旅人","summary":"正在尋找遺跡","personality":"冷靜","alternateGreetings":["你好"]}""",
            ProfileType.CHARACTER,
        )!!

        assertEquals("旅人", draft.name)
        assertEquals("冷靜", draft.personality)
        assertEquals(listOf("你好"), draft.alternateGreetings)
    }

    @Test
    fun unknownJsonNeedsAiOrganization() {
        assertNull(profileDraftFromOwnJson("""{"random":"value"}""", ProfileType.PERSONA))
    }

    @Test
    fun aiWorldJsonParsesMultipleEntries() {
        val draft = parseAiWorldSetDraft(
            """{"name":"北境","entries":[{"title":"冰橋","keywords":["冰橋","河谷"],"content":"冬季可通行","alwaysInclude":false},{"title":"魔法","keywords":[],"content":"施法需要媒介","alwaysInclude":true}]}""",
        )

        assertEquals("北境", draft.name)
        assertEquals(2, draft.entries.size)
        assertEquals(listOf("冰橋", "河谷"), draft.entries.first().keywords)
        assertTrue(draft.entries.last().alwaysInclude)
    }
}
