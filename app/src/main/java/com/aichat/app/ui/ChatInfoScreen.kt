package com.aichat.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.roundToInt
@Composable
internal fun ChatInfoScreen(viewModel: ChatViewModel, language: AppLanguage) {
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
    Scaffold(topBar = { CompactTopBar(language.pick("對話資訊", "对话信息"), navigationIcon = { Back(language, viewModel::openCurrentChat) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoSection {
                Text(language.pick("聊天背景", "聊天背景"), fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { backgroundLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text(language.pick("上傳背景圖", "上传背景图")) }
                    if (current.backgroundImagePath.isNotBlank()) {
                        OutlinedButton(
                            onClick = viewModel::clearConversationBackground,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                        ) { Text(language.pick("移除背景", "移除背景")) }
                    }
                }
                Text(
                    if (current.backgroundImagePath.isBlank()) language.pick("目前使用預設背景。", "目前使用默认背景。") else language.pick("已設定自訂背景圖。", "已设置自定义背景图。"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    language.pick(
                        "對話框透明度：${(bubbleTransparency * 100).roundToInt()}%",
                        "对话框透明度：${(bubbleTransparency * 100).roundToInt()}%",
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = bubbleTransparency,
                    onValueChange = { bubbleTransparency = it },
                    valueRange = 0f..0.65f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    onValueChangeFinished = {
                        viewModel.updateMessageBubbleOpacity(1f - bubbleTransparency)
                    },
                )
                Text(
                    language.pick("越高越透明，最低仍保留可讀性。", "越高越透明，最低仍保留可读性。"),
                    style = MaterialTheme.typography.bodySmall,
                )
                }
            }
            item {
                InfoSection {
                    Text("Persona", fontWeight = FontWeight.Bold)
                    SelectRow(language.pick("不指定 Persona", "不指定 Persona"), current.personaId == null) { viewModel.updateConversationPersona(null) }
                    personas.forEach { persona ->
                        SelectRow(persona.name, current.personaId == persona.id) { viewModel.updateConversationPersona(persona.id) }
                    }
                }
            }
            item {
                InfoSection {
                Text(language.pick("世界設定集", "世界设定集"), fontWeight = FontWeight.Bold)
                Text(
                    if (activeIds.isEmpty()) language.pick("目前未啟用世界設定集。", "目前未启用世界设定集。")
                    else language.pick("已啟用 ${activeIds.size} 個世界設定集。", "已启用 ${activeIds.size} 个世界设定集。"),
                    style = MaterialTheme.typography.bodySmall,
                )
                sets.forEach { set ->
                    val count = countMap[set.id] ?: 0
                    CheckRow(
                        language.pick("${set.name}（$count 條）", "${set.name}（$count 条）"),
                        set.id in activeIds,
                    ) { viewModel.toggleConversationWorldSet(set.id) }
                }
                }
            }
            item {
                InfoSection {
                        Text(language.pick("手動壓縮對話", "手动压缩对话"), fontWeight = FontWeight.Bold)
                        Text(
                            if (current.summary.isBlank()) language.pick("目前沒有較早對話摘要。", "目前没有较早对话摘要。")
                            else language.pick("目前已有較早對話摘要，之後送給 AI 時會用摘要取代已壓縮的舊訊息。", "目前已有较早对话摘要，之后发送给 AI 时会用摘要取代已压缩的旧消息。"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (current.summary.isNotBlank()) {
                            Text(current.summary, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            OutlinedButton(onClick = { summaryModeMenu = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                                Text(manualSummaryModeLabel(summaryMode, language))
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
                        OutlinedTextField(
                            keepRecentText,
                            { keepRecentText = it.filter(Char::isDigit).take(3) },
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            label = { Text(language.pick("保留最近訊息數", "保留最近消息数")) },
                            supportingText = { Text(language.pick("本次有效，範圍 1 到 100。", "仅本次有效，范围 1 到 100。")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Text(
                            when {
                                keepRecentCount == null -> language.pick("請輸入 1 到 100 的數字。", "请输入 1 到 100 的数字。")
                                summaryPlan?.messagesToSummarize?.isEmpty() == true -> language.pick("目前沒有足夠的較早訊息可以壓縮。", "目前没有足够的较早消息可以压缩。")
                                else -> language.pick("將壓縮 ${summaryPlan?.messagesToSummarize?.size ?: 0} 則較早訊息，保留最近 $keepRecentCount 則。", "将压缩 ${summaryPlan?.messagesToSummarize?.size ?: 0} 条较早消息，保留最近 $keepRecentCount 条。")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { viewModel.manuallySummarizeConversation(summaryMode, keepRecentCount ?: 20) },
                            enabled = canSummarize,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Text(
                                if (isSummarizing) language.pick("壓縮中...", "压缩中...")
                                else language.pick("開始手動壓縮", "开始手动压缩"),
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun InfoSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
