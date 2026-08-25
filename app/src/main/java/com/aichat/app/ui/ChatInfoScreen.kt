package com.aichat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.AppLanguage
import kotlin.math.roundToInt

@Composable
internal fun ChatInfoScreen(viewModel: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val conversation by viewModel.selectedConversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val sets by viewModel.worldSets.collectAsStateWithLifecycle()
    val activeIds by viewModel.activeWorldSetIds.collectAsStateWithLifecycle()
    val entryCounts by viewModel.worldEntryCounts.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val isSummarizing by viewModel.isSummarizingConversation.collectAsStateWithLifecycle()
    val countMap = remember(entryCounts) { entryCounts.associate { it.worldSetId to it.count } }
    val current = conversation ?: return
    var bubbleTransparency by remember(current.id, current.messageBubbleOpacity) {
        mutableStateOf(1f - current.messageBubbleOpacity.coerceIn(0.35f, 1f))
    }
    var summaryMode by remember(current.id) { mutableStateOf(ManualSummaryMode.UN_SUMMARIZED) }
    var keepRecentText by remember(current.id) { mutableStateOf("20") }
    var summaryModeMenu by remember { mutableStateOf(false) }
    val keepRecentCount = keepRecentText.toIntOrNull()?.coerceIn(1, 100)
    val summaryPlan = remember(current, messages, keepRecentCount, summaryMode) {
        keepRecentCount?.let { conversationSummaryPlan(current, messages, it, summaryMode) }
    }
    val canSummarize = keepRecentCount != null &&
        summaryPlan?.messagesToSummarize?.isNotEmpty() == true &&
        !isStreaming &&
        !isSummarizing
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setConversationBackground)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .padding(start = 6.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) { Back(language, onBack) }
                    Text(
                        language.pick("對話資訊", "对话信息"),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(50.dp))
                }
                Hairline()
            }
        },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = onBack,
                    Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(language.pick("儲存並返回", "保存并返回"), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 22.dp, top = 8.dp, end = 22.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHead(language.pick("背景圖", "背景图")) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { backgroundLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(language.pick("上傳圖片", "上传图片"))
                    }
                    if (current.backgroundImagePath.isNotBlank()) {
                        OutlinedButton(
                            onClick = viewModel::clearConversationBackground,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(language.pick("移除", "移除"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                SectionHead(
                    language.pick("泡泡・背景透明度", "泡泡・背景透明度"),
                    trailing = "${(bubbleTransparency * 100).roundToInt()}%",
                )
            }
            item {
                Column {
                    Slider(
                        value = bubbleTransparency,
                        onValueChange = { bubbleTransparency = it },
                        valueRange = 0f..0.65f,
                        onValueChangeFinished = {
                            viewModel.updateMessageBubbleOpacity(1f - bubbleTransparency)
                        },
                    )
                    Text(
                        language.pick("越高越透明，最低仍保留可讀性。", "越高越透明，最低仍保留可读性。"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SectionHead("對話 PERSONA") }
            item {
                RadioCard(
                    selected = current.personaId == null,
                    title = language.pick("不指定 Persona", "不指定 Persona"),
                    subtitle = language.pick("此對話不套用 Persona", "此对话不套用 Persona"),
                    onClick = { viewModel.updateConversationPersona(null) },
                )
            }
            items(personas, key = { it.id }) { persona ->
                RadioCard(
                    selected = current.personaId == persona.id,
                    title = persona.name,
                    subtitle = persona.summary.ifBlank { null },
                    onClick = { viewModel.updateConversationPersona(persona.id) },
                )
            }
            item { SectionHead(language.pick("世界設定", "世界设定")) }
            items(sets, key = { it.id }) { set ->
                val count = countMap[set.id] ?: 0
                CheckCard(
                    checked = set.id in activeIds,
                    title = set.name,
                    subtitle = language.pick("$count 條目", "$count 条目"),
                    onClick = { viewModel.toggleConversationWorldSet(set.id) },
                )
            }
            item { SectionHead(language.pick("手動壓縮", "手动压缩")) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (current.summary.isBlank()) language.pick("目前沒有較早對話摘要。", "目前没有较早对话摘要。")
                        else language.pick("目前已有較早對話摘要，之後送給 AI 時會用摘要取代已壓縮的舊訊息。", "目前已有较早对话摘要，之后发送给 AI 时会用摘要取代已压缩的旧消息。"),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (current.summary.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFE3E3E8) else Color(0xFF3A3A3C),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                current.summary,
                                Modifier
                                    .padding(12.dp)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Box {
                        OutlinedButton(
                            onClick = { summaryModeMenu = true },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(manualSummaryModeLabel(summaryMode, language), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        DropdownMenu(summaryModeMenu, { summaryModeMenu = false }) {
                            ManualSummaryMode.entries.forEach { option ->
                                DropdownMenuItem(
                                    { Text(manualSummaryModeLabel(option, language)) },
                                    {
                                        summaryMode = option
                                        summaryModeMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(language.pick("保留最近訊息數", "保留最近消息数"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier
                                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "−",
                                Modifier
                                    .clickable {
                                        val v = ((keepRecentText.toIntOrNull() ?: 20) - 1).coerceIn(1, 100)
                                        keepRecentText = v.toString()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                keepRecentText,
                                Modifier.width(44.dp),
                                textAlign = TextAlign.Center,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "+",
                                Modifier
                                    .clickable {
                                        val v = ((keepRecentText.toIntOrNull() ?: 20) + 1).coerceIn(1, 100)
                                        keepRecentText = v.toString()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Text(
                        when {
                            keepRecentCount == null -> language.pick("請輸入 1 到 100 的數字。", "请输入 1 到 100 的数字。")
                            summaryPlan?.messagesToSummarize?.isEmpty() == true -> language.pick("目前沒有足夠的較早訊息可以壓縮。", "目前没有足够的较早消息可以压缩。")
                            else -> language.pick("將壓縮 ${summaryPlan?.messagesToSummarize?.size ?: 0} 則較早訊息，保留最近 $keepRecentCount 則。", "将压缩 ${summaryPlan?.messagesToSummarize?.size ?: 0} 条较早消息，保留最近 $keepRecentCount 条。")
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { viewModel.manuallySummarizeConversation(summaryMode, keepRecentCount ?: 20) },
                        enabled = canSummarize,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            if (isSummarizing) language.pick("壓縮中...", "压缩中...")
                            else language.pick("開始手動壓縮", "开始手动压缩"),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        trailing?.let {
            Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
