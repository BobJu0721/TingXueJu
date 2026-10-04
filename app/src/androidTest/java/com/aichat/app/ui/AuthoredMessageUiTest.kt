package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.MessageKind
import com.aichat.app.data.MessageVersionEntity
import com.aichat.app.ui.theme.iosColorScheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 手動創作訊息的呈現與操作限制。
 *
 * 作者身份要看得出來，私人註記不能出現任何會把它送給模型的操作。
 */
@RunWith(AndroidJUnit4::class)
class AuthoredMessageUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrationAndAuthoredLineShowTheirAuthorIdentity() {
        rule.setContent {
            Host(
                narration(),
                authored(),
            )
        }
        rule.onNodeWithText("旁白").assertIsDisplayed()
        rule.onNodeWithText("掌櫃・手寫").assertIsDisplayed()
        rule.onNodeWithText("三日後，眾人抵達山門。").assertIsDisplayed()
        rule.onNodeWithText("樓上的房間，今晚不能進去。").assertIsDisplayed()
    }

    @Test fun privateNoteIsLabelledAndHasNoModelFacingAction() {
        rule.setContent { Host(privateNote()) }

        rule.onNodeWithText("私人註記・不提供給 AI").assertIsDisplayed()
        rule.onNodeWithText("後面可以考慮讓掌櫃與使者是舊識。").assertIsDisplayed()
        // 沒有「讓 AI 接話」、「重新發送」或「生成另一版」。
        rule.onNodeWithContentDescription("讓 AI 接話").assertDoesNotExist()
        rule.onNodeWithContentDescription("重新發送").assertDoesNotExist()
        rule.onNodeWithContentDescription("生成另一版").assertDoesNotExist()
    }

    @Test fun authoredLineOffersLetAiReplyInsteadOfRegenerate() {
        rule.setContent { Host(authored()) }

        // 手寫角色台詞的 ⟳ 是「讓 AI 接話」，不是「生成另一版」。
        rule.onNodeWithContentDescription("讓 AI 接話").assertIsDisplayed()
        rule.onNodeWithContentDescription("生成另一版").assertDoesNotExist()
    }

    @Test fun privateNoteMenuHasNoToggleForAiVisibility() {
        rule.setContent { Host(privateNote()) }

        rule.onNodeWithContentDescription("更多功能").performClick()
        rule.onNodeWithText("刪除訊息").assertIsDisplayed()
        rule.onNodeWithText("不提供給 AI").assertDoesNotExist()
        rule.onNodeWithText("恢復提供給 AI").assertDoesNotExist()
        rule.onNodeWithText("續寫").assertDoesNotExist()
    }

    @Composable
    private fun Host(vararg messages: MessageEntity) {
        MaterialTheme(colorScheme = remember { iosColorScheme(dark = false) }) {
            Column(Modifier.fillMaxSize()) {
                messages.forEach { message ->
                    MessageBubble(
                        message = message,
                        versions = listOf(versionOf(message)),
                        generationContext = null,
                        language = AppLanguage.TRADITIONAL_CHINESE,
                        bubbleOpacity = 1f,
                        characterName = "AI",
                        characterSeed = "seed",
                        isGenerating = false,
                        canContinue = false,
                        highlighted = false,
                        historyActionsEnabled = true,
                        actionsVisible = true,
                        onToggleActions = { },
                        onEdit = { _, _ -> },
                        onSelectVersion = { _, _ -> },
                        onGenerateAlternative = { },
                        onAiReplyFrom = { },
                        onEditAuthored = { },
                        onAnswerFrom = { },
                        onContinue = { },
                        onDelete = { },
                        onToggleExcluded = { },
                    )
                }
            }
        }
    }

    private fun versionOf(message: MessageEntity) = MessageVersionEntity(
        id = message.currentVersionId,
        messageId = message.id,
        versionNumber = 1,
        content = message.content,
        createdAt = message.createdAt,
        authorName = message.authorName,
    )

    private fun narration() = authored("n", "三日後，眾人抵達山門。", MessageKind.NARRATION, null)
    private fun authored() = authored("a", "樓上的房間，今晚不能進去。", MessageKind.AUTHORED_CHARACTER, "掌櫃")
    private fun privateNote() = authored("p", "後面可以考慮讓掌櫃與使者是舊識。", MessageKind.PRIVATE_NOTE, null)

    private fun authored(id: String, content: String, kind: MessageKind, author: String?) = MessageEntity(
        id = id,
        conversationId = "c",
        role = "user",
        content = content,
        createdAt = 1,
        currentVersionId = "$id-v1",
        sortOrder = 1,
        kind = kind,
        authorName = author,
    )
}
