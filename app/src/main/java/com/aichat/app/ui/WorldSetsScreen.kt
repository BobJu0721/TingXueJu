package com.aichat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorldSetsScreen(viewModel: WorldSetsViewModel, onBack: () -> Unit, language: AppLanguage) {
    val sets by viewModel.worldSets.collectAsStateWithLifecycle()
    val templates = viewModel.worldTemplates
    var showTemplates by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WorldSetEntity?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importDocument(it, ImportTarget.WORLD_SET) }
    }

    Scaffold(
        topBar = {
            CompactTopBar(
                language.pick("世界設定集", "世界设定集"),
                navigationIcon = { Back(language, onBack) },
                actions = {
                    TextButton(onClick = { showTemplates = true }) { Text(language.pick("模板", "模板")) }
                    IconButton(onClick = { launcher.launch(DOCUMENT_TYPES) }) {
                        Icon(Icons.Default.UploadFile, language.pick("匯入世界設定", "导入世界设定"))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::newWorldSet,
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) { Icon(Icons.Default.Add, language.pick("新增設定集", "新增设定集")) }
        },
    ) { padding ->
        if (sets.isEmpty()) {
            EmptyState(
                language.pick("還沒有世界設定集", "还没有世界设定集"),
                language.pick("可使用模板、手動新增條目，或匯入文件讓 AI 拆成關鍵詞設定。", "可使用模板、手动新增条目，或导入文件让 AI 拆成关键词设定。"),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sets, key = { it.id }) { set ->
                    val cardShape = RoundedCornerShape(11.dp)
                    Card(
                        Modifier.fillMaxWidth().clippedCombinedClickable(
                            cardShape,
                            onClick = { viewModel.editWorldSet(set) },
                            onLongClick = { pendingDelete = set },
                        ),
                        shape = cardShape,
                        colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(set.name, fontWeight = FontWeight.Bold)
                                if (set.overview.isNotBlank()) {
                                    Text(set.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { set ->
        DeleteConfirmDialog(
            title = language.pick("刪除世界設定集", "删除世界设定集"),
            message = set.name,
            language = language,
            onConfirm = {
                viewModel.deleteWorldSet(set)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showTemplates) {
        AlertDialog(
            onDismissRequest = { showTemplates = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(language.pick("使用世界觀模板", "使用世界观模板")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { template ->
                        Button(
                            onClick = {
                                viewModel.createWorldTemplate(template)
                                showTemplates = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(template.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTemplates = false }) { Text(language.pick("取消", "取消")) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorldSetEditScreen(viewModel: WorldSetsViewModel, language: AppLanguage, onBack: () -> Unit) {
    val worldSet by viewModel.editingWorldSet.collectAsStateWithLifecycle()
    val entries by viewModel.editingWorldEntries.collectAsStateWithLifecycle()
    var name by remember(worldSet?.id) { mutableStateOf(worldSet?.name.orEmpty()) }
    var overview by remember(worldSet?.id) { mutableStateOf(worldSet?.overview.orEmpty()) }
    var depth by remember(worldSet?.id) { mutableStateOf((worldSet?.scanDepth ?: 10).toString()) }
    var editingEntry by remember { mutableStateOf<WorldEntryEntity?>(null) }
    var pendingEntryDelete by remember { mutableStateOf<WorldEntryEntity?>(null) }
    var showEntryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(worldSet?.id, name, overview, depth) {
        val current = worldSet ?: return@LaunchedEffect
        val scanDepth = depth.toIntOrNull() ?: current.scanDepth
        if (name.trim() == current.name && overview.trim() == current.overview && scanDepth.coerceIn(1, 100) == current.scanDepth) return@LaunchedEffect
        delay(600)
        viewModel.updateWorldSetMetadata(name, overview, scanDepth)
    }

    Scaffold(
        topBar = { CompactTopBar(language.pick("編輯世界設定集", "编辑世界设定集"), navigationIcon = { Back(language, onBack) }) },
        floatingActionButton = {
            if (worldSet != null) {
                FloatingActionButton(
                    onClick = { editingEntry = null; showEntryDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) { Icon(Icons.Default.Add, language.pick("新增條目", "新增条目")) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("設定集名稱", "设定集名称")) })
                OutlinedTextField(
                    overview,
                    { overview = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(language.pick("一句話概括", "一句话概括")) },
                    supportingText = {
                        Text(language.pick(
                            "包含：時代與科技水準、主要舞台與關鍵地點、核心衝突、主要勢力/陣營、力量/資源體系、社會規則或禁忌、角色相關重大歷史事件。",
                            "包含：时代与科技水平、主要舞台与关键地点、核心冲突、主要势力/阵营、力量/资源体系、社会规则或禁忌、角色相关重大历史事件。",
                        ))
                    },
                    minLines = 3,
                )
                OutlinedTextField(
                    depth,
                    { depth = it.filter(Char::isDigit) },
                    Modifier.fillMaxWidth(),
                    label = { Text(language.pick("掃描最近訊息數", "扫描最近消息数")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (worldSet == null) item { Text(language.pick("正在建立設定集...", "正在建立设定集...")) }
            items(entries, key = { it.id }) { entry ->
                val cardShape = RoundedCornerShape(11.dp)
                Card(
                    Modifier.fillMaxWidth().clippedCombinedClickable(
                        cardShape,
                        onClick = { editingEntry = entry; showEntryDialog = true },
                        onLongClick = { pendingEntryDelete = entry },
                    ),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, fontWeight = FontWeight.Bold)
                            Text(if (entry.alwaysInclude) language.pick("每次附加", "每次附加") else jsonStrings(entry.keywordsJson).joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    pendingEntryDelete?.let { entry ->
        DeleteConfirmDialog(
            title = language.pick("刪除條目", "删除条目"),
            message = entry.title,
            language = language,
            onConfirm = {
                viewModel.deleteWorldEntry(entry)
                pendingEntryDelete = null
            },
            onDismiss = { pendingEntryDelete = null },
        )
    }

    if (showEntryDialog) {
        WorldEntryDialog(editingEntry, language, { showEntryDialog = false }) { id, title, keys, content, always, enabled ->
            viewModel.saveWorldEntry(id, title, keys, content, always, enabled)
            showEntryDialog = false
        }
    }
}

@Composable
private fun WorldEntryDialog(
    entry: WorldEntryEntity?,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, String, Boolean, Boolean) -> Unit,
) {
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var keys by remember(entry?.id) { mutableStateOf(entry?.let { jsonStrings(it.keywordsJson).joinToString(", ") }.orEmpty()) }
    var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    var always by remember(entry?.id) { mutableStateOf(entry?.alwaysInclude ?: false) }
    var enabled by remember(entry?.id) { mutableStateOf(entry?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(language.pick("世界設定條目", "世界设定条目")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(language.pick("標題", "标题")) })
                OutlinedTextField(keys, { keys = it }, label = { Text(language.pick("關鍵詞", "关键词")) }, supportingText = { Text(language.pick("用逗號分隔", "用逗号分隔")) })
                OutlinedTextField(content, { content = it }, label = { Text(language.pick("內容", "内容")) }, minLines = 4)
                DetailedToggleRow(
                    title = language.pick("每次對話都送出", "每次对话都送出"),
                    detail = language.pick("開啟後不需要命中關鍵詞，每次生成都會把這條設定送給模型。", "开启后不需要命中关键词，每次生成都会把这条设定发送给模型。"),
                    checked = always,
                ) { always = it }
                DetailedToggleRow(
                    title = language.pick("使用此條目", "使用此条目"),
                    detail = language.pick("關閉後這條設定不會被關鍵詞觸發，也不會被每次送出。", "关闭后这条设定不会被关键词触发，也不会被每次发送。"),
                    checked = enabled,
                ) { enabled = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(entry?.id, title, keys, content, always, enabled) }) { Text(language.pick("儲存", "保存")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(language.pick("取消", "取消")) } },
    )
}
