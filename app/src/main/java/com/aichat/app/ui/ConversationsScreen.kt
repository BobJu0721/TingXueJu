package com.aichat.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        containerColor = Color.Transparent,
        topBar = {
            LargeTitleHeader(
                title = language.pick("對話", "对话"),
                countText = if (conversations.isEmpty()) null
                            else language.pick("${conversations.size} 則對話", "${conversations.size} 则对话"),
                onAdd = { newChatViewModel.beginNewChat() },
                addDescription = language.pick("新增對話", "新增对话"),
            )
        },
        bottomBar = { if (showBottomBar) RootBottomBar(Screen.CONVERSATIONS, language, onRootSelected) },
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyState(
                language.pick("還沒有對話", "还没有对话"),
                language.pick("按右上角＋新增一般對話，或從角色頁開始劇情。", "按右上角＋新增一般对话，或从角色页开始剧情。"),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 14.dp, top = 6.dp, end = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    val cardShape = RoundedCornerShape(16.dp)
                    Card(
                        Modifier.fillMaxWidth().clippedCombinedClickable(
                            cardShape,
                            onClick = { viewModel.selectConversation(conversation.id) },
                            onLongClick = { pendingDelete = conversation },
                        ),
                        shape = cardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarCircle(conversation.title, conversation.id)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conversation.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    relativeTimeLabel(conversation.updatedAt),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
                item {
                    Text(
                        language.pick("點擊開啟對話，長按刪除", "点击开启对话，长按删除"),
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
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
