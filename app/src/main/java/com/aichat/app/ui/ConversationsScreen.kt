package com.aichat.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.ConversationEntity

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationsScreen(
    viewModel: ChatViewModel,
    newChatViewModel: NewChatViewModel,
    onRootSelected: (Screen) -> Unit,
    language: AppLanguage,
    showBottomBar: Boolean = true,
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    Scaffold(
        topBar = { CompactTopBar(language.pick("聽雪居", "听雪居")) },
        bottomBar = { if (showBottomBar) RootBottomBar(Screen.CONVERSATIONS, language, onRootSelected) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { newChatViewModel.beginNewChat() },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) { Icon(Icons.Default.Add, language.pick("新增對話", "新增对话")) }
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyState(
                language.pick("還沒有對話", "还没有对话"),
                language.pick("按右下角新增一般對話，或從角色頁開始劇情。", "按右下角新增一般对话，或从角色页开始剧情。"),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    Card(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { viewModel.selectConversation(conversation.id) },
                            onLongClick = { pendingDelete = conversation },
                        ),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(conversation.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { conversation ->
        DeleteConfirmDialog(
            title = language.pick("刪除對話", "删除对话"),
            message = conversation.title,
            language = language,
            onConfirm = {
                viewModel.deleteConversation(conversation)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
