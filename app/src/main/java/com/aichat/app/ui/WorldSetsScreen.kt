package com.aichat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val entryCounts by viewModel.worldEntryCounts.collectAsStateWithLifecycle()
    val countMap = remember(entryCounts) { entryCounts.associate { it.worldSetId to it.count } }
    val templates = viewModel.worldTemplates
    var showTemplates by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WorldSetEntity?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importDocument(it, ImportTarget.WORLD_SET) }
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
                        language.pick("世界設定集", "世界设定集"),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .clickable { showTemplates = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.AutoAwesome, language.pick("模板", "模板"), modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .clickable { launcher.launch(DOCUMENT_TYPES) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.UploadFile, language.pick("匯入世界設定", "导入世界设定"), modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Hairline()
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::newWorldSet,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
            ) { Icon(Icons.Default.Add, language.pick("新增設定集", "新增设定集")) }
        },
    ) { padding ->
        if (sets.isEmpty()) {
            EmptyState(
                language.pick("還沒有任何設定集", "还没有任何设定集"),
                language.pick("右下「＋」手動新增，\n上方 ✦ 從模板快速建立，\n或匯入讓 AI 從文件自動整理。", "右下「＋」手动新增，\n上方 ✦ 从模板快速建立，\n或汇入让 AI 从文件自动整理。"),
                Modifier.padding(padding),
                emoji = "📜",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 22.dp, top = 8.dp, end = 22.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(sets, key = { it.id }) { set ->
                    val count = countMap[set.id] ?: 0
                    val cardShape = RoundedCornerShape(20.dp)
                    Card(
                        Modifier.fillMaxWidth().clippedCombinedClickable(
                            cardShape,
                            onClick = { viewModel.editWorldSet(set) },
                            onLongClick = { pendingDelete = set },
                        ),
                        shape = cardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconTile(Icons.AutoMirrored.Filled.MenuBook)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    set.name,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    language.pick("$count 條目", "$count 条目"),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
                item {
                    Text(
                        language.pick("點擊編輯，長按刪除", "点击编辑，长按删除"),
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
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
                        worldSet?.name?.ifBlank { null } ?: language.pick("編輯世界設定集", "编辑世界设定集"),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(50.dp))
                }
                Hairline()
            }
        },
        floatingActionButton = {
            if (worldSet != null) {
                FloatingActionButton(
                    onClick = { editingEntry = null; showEntryDialog = true },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ) { Icon(Icons.Default.Add, language.pick("新增條目", "新增条目")) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 22.dp, top = 8.dp, end = 22.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(language.pick("名稱", "名称"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(language.pick("設定集名稱", "设定集名称"), fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(language.pick("世界概要", "世界概要"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        overview,
                        { overview = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(language.pick("一句話概括", "一句话概括"), fontSize = 14.sp) },
                        minLines = 3,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                    Text(
                        language.pick(
                            "包含：時代與科技水準、主要舞台與關鍵地點、核心衝突、主要勢力/陣營、力量/資源體系、社會規則或禁忌、角色相關重大歷史事件。",
                            "包含：时代与科技水平、主要舞台与关键地点、核心冲突、主要势力/阵营、力量/资源体系、社会规则或禁忌、角色相关重大历史事件。",
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(language.pick("掃描訊息數", "扫描消息数"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "−",
                                Modifier
                                    .clickable {
                                        depth = ((depth.toIntOrNull() ?: 10) - 1).coerceIn(1, 100).toString()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                depth,
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
                                        depth = ((depth.toIntOrNull() ?: 10) + 1).coerceIn(1, 100).toString()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            language.pick("每次生成掃描最近 N 則訊息（1～100）", "每次生成扫描最近 N 则消息（1～100）"),
                            Modifier.weight(1f),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionTitle(language.pick("條目", "条目"), badge = "${entries.size}") }
            if (worldSet == null) item { Text(language.pick("正在建立設定集...", "正在建立设定集..."), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries, key = { it.id }) { entry ->
                val cardShape = RoundedCornerShape(16.dp)
                Card(
                    Modifier.fillMaxWidth().clippedCombinedClickable(
                        cardShape,
                        onClick = { editingEntry = entry; showEntryDialog = true },
                        onLongClick = { pendingEntryDelete = entry },
                    ),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            val keywords = jsonStrings(entry.keywordsJson)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                keywords.take(3).forEach { kw ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(kw, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (entry.alwaysInclude) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(language.pick("每次附加", "每次附加"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (entry.enabled) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
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
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                if (entry == null) language.pick("新增條目", "新增条目") else language.pick("編輯條目", "编辑条目"),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                DialogField(
                    label = language.pick("標題", "标题"),
                    required = true,
                ) {
                    OutlinedTextField(
                        title, { title = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(language.pick("例如：月蝕儀式", "例如：月蚀仪式"), fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                }
                DialogField(
                    label = language.pick("關鍵詞", "关键词"),
                    required = true,
                    hint = language.pick("以逗號分隔：銀湖, 儀式", "以逗号分隔：银湖, 仪式"),
                ) {
                    OutlinedTextField(
                        keys, { keys = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(language.pick("銀湖, 儀式", "银湖, 仪式"), fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                }
                DialogField(
                    label = language.pick("內容", "内容"),
                    required = true,
                    hint = language.pick("命中關鍵詞時附加給 AI 的世界知識……", "命中关键词时附加给 AI 的世界知识……"),
                ) {
                    OutlinedTextField(
                        content, { content = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(language.pick("命中關鍵詞時附加給 AI 的世界知識……", "命中关键词时附加给 AI 的世界知识……"), fontSize = 14.sp) },
                        minLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(language.pick("每次送出時附加", "每次送出时附加"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(language.pick("不掃描關鍵詞，永遠注入", "不扫描关键词，永远注入"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(always, { always = it }, colors = minimalSwitchColors())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(language.pick("啟用", "启用"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(language.pick("關閉後此條目不會被觸發", "关闭后此条目不会被触发"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(enabled, { enabled = it }, colors = minimalSwitchColors())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(entry?.id, title, keys, content, always, enabled) },
                shape = RoundedCornerShape(14.dp),
            ) { Text(language.pick("儲存條目", "保存条目"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(language.pick("取消", "取消"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun DialogField(
    label: String,
    required: Boolean,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (required) {
                Spacer(Modifier.width(2.dp))
                Text("*", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
        }
        content()
        hint?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
