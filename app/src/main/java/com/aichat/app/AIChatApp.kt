package com.aichat.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.ProfileType
import com.aichat.app.data.Provider
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity

private val DOCUMENT_TYPES = arrayOf(
    "text/plain",
    "application/json",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/octet-stream",
)

@Composable
fun AIChatApp(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val showUnsafeWarning by viewModel.showUnsafeHttpWarning.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); viewModel.clearNotice() } }

    MaterialTheme(colorScheme = if (settings.darkTheme) darkColorScheme() else lightColorScheme()) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outer ->
            Box(Modifier.padding(outer)) {
                when (screen) {
                    Screen.CONVERSATIONS -> ConversationsScreen(viewModel)
                    Screen.CHARACTERS -> ProfilesScreen(viewModel, ProfileType.CHARACTER)
                    Screen.LIBRARY -> LibraryScreen(viewModel)
                    Screen.SETTINGS -> SettingsScreen(viewModel, settings)
                    Screen.CHAT -> ChatScreen(viewModel)
                    Screen.MODELS -> ModelsScreen(viewModel, settings.model)
                    Screen.PROFILE_EDIT -> ProfileEditScreen(viewModel)
                    Screen.WORLD_SETS -> WorldSetsScreen(viewModel)
                    Screen.WORLD_SET_EDIT -> WorldSetEditScreen(viewModel)
                    Screen.NEW_CHAT -> NewChatScreen(viewModel)
                    Screen.CHAT_INFO -> ChatInfoScreen(viewModel)
                }
                if (isImporting) LoadingOverlay("AI 正在整理文件...")
            }
        }
        error?.let { current ->
            ErrorDialog(current, viewModel::clearError, viewModel::openSettings, viewModel::trimOldestContextAndRetry) {
                viewModel.clearError(); viewModel.beginNewChat()
            }
        }
        pendingImport?.let { pending ->
            AlertDialog(
                onDismissRequest = viewModel::dismissPendingImport,
                title = { Text("確認傳送文件") },
                text = {
                    Text("將把「${pending.document.name}」的 ${pending.document.text.length} 個字元傳送給 ${settings.provider.label} / ${settings.model}，預估最多 ${pending.estimatedCalls} 次 API 呼叫。請確認文件不含不希望傳出的私人內容。")
                },
                confirmButton = { TextButton(onClick = viewModel::confirmPendingImport) { Text("同意並整理") } },
                dismissButton = { TextButton(onClick = viewModel::dismissPendingImport) { Text("取消") } },
            )
        }
        if (showUnsafeWarning) {
            AlertDialog(
                onDismissRequest = viewModel::dismissUnsafeHttp,
                title = { Text("HTTP 端點警告") },
                text = { Text("目前端點使用未加密 HTTP。API Key 和聊天內容可能外洩，確定仍要傳送嗎？") },
                confirmButton = { TextButton(onClick = viewModel::confirmUnsafeHttp) { Text("仍要傳送") } },
                dismissButton = { TextButton(onClick = viewModel::dismissUnsafeHttp) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun RootBottomBar(viewModel: MainViewModel, selected: Screen) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactBottomItem("對話", Icons.Default.Chat, selected == Screen.CONVERSATIONS, viewModel::openConversations)
            CompactBottomItem("角色", Icons.Default.Person, selected == Screen.CHARACTERS, viewModel::openCharacters)
            CompactBottomItem("資料庫", Icons.Default.Storage, selected == Screen.LIBRARY, viewModel::openLibrary)
            CompactBottomItem("設定", Icons.Default.Settings, selected == Screen.SETTINGS, viewModel::openSettings)
        }
    }
}

@Composable
private fun RowScope.CompactBottomItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsScreen(viewModel: MainViewModel) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("AI Chat") }) },
        bottomBar = { RootBottomBar(viewModel, Screen.CONVERSATIONS) },
        floatingActionButton = { FloatingActionButton(onClick = { viewModel.beginNewChat() }) { Icon(Icons.Default.Add, "新增對話") } },
    ) { padding ->
        if (conversations.isEmpty()) EmptyState("還沒有對話", "按右下角新增一般對話，或從角色頁開始劇情。", Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.id }) { conversation ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.selectConversation(conversation.id) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(conversation.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { viewModel.deleteConversation(conversation) }) { Icon(Icons.Default.Delete, "刪除對話") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesScreen(viewModel: MainViewModel, type: ProfileType) {
    val profiles by (if (type == ProfileType.CHARACTER) viewModel.characters else viewModel.personas).collectAsStateWithLifecycle()
    val importTarget = if (type == ProfileType.CHARACTER) ImportTarget.CHARACTER else ImportTarget.PERSONA
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importDocument(it, importTarget) } }
    val title = if (type == ProfileType.CHARACTER) "角色" else "Persona"
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, actions = { IconButton(onClick = { launcher.launch(DOCUMENT_TYPES) }) { Icon(Icons.Default.UploadFile, "匯入文件") } }) },
        bottomBar = { if (type == ProfileType.CHARACTER) RootBottomBar(viewModel, Screen.CHARACTERS) },
        floatingActionButton = { FloatingActionButton(onClick = { viewModel.newProfile(type) }) { Icon(Icons.Default.Add, "新增$title") } },
    ) { padding ->
        if (profiles.isEmpty()) EmptyState("還沒有$title", "可以手動建立，或從 TXT、JSON、DOCX 文件交給 AI 整理。", Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id }) { profile -> ProfileRow(profile, type == ProfileType.CHARACTER, viewModel) }
        }
    }
}

@Composable
private fun ProfileRow(profile: ProfileEntity, canChat: Boolean, viewModel: MainViewModel) {
    Card(Modifier.fillMaxWidth().clickable { viewModel.editProfile(profile) }) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold)
                if (profile.summary.isNotBlank()) Text(profile.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (canChat) IconButton(onClick = { viewModel.beginNewChat(profile.id) }) { Icon(Icons.Default.Chat, "開始聊天") }
            IconButton(onClick = { viewModel.editProfile(profile) }) { Icon(Icons.Default.Edit, "編輯") }
            IconButton(onClick = { viewModel.deleteProfile(profile) }) { Icon(Icons.Default.Delete, "刪除") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(viewModel: MainViewModel) {
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importDocument(it, ImportTarget.PERSONA) } }
    Scaffold(topBar = { TopAppBar(title = { Text("資料庫") }) }, bottomBar = { RootBottomBar(viewModel, Screen.LIBRARY) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = viewModel::openWorldSets)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(12.dp))
                        Column { Text("世界設定集", fontWeight = FontWeight.Bold); Text("地點、人物關係與規則", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Persona", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { launcher.launch(DOCUMENT_TYPES) }) { Icon(Icons.Default.UploadFile, "匯入 Persona") }
                    IconButton(onClick = { viewModel.newProfile(ProfileType.PERSONA) }) { Icon(Icons.Default.Add, "新增 Persona") }
                }
            }
            if (personas.isEmpty()) item { Text("尚未建立 Persona。你仍然可以不指定身份直接聊天。") }
            items(personas, key = { it.id }) { ProfileRow(it, false, viewModel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(viewModel: MainViewModel) {
    val source by viewModel.editingProfile.collectAsStateWithLifecycle()
    val draft = source ?: return
    var name by remember { mutableStateOf(draft.name) }
    var summary by remember { mutableStateOf(draft.summary) }
    var personality by remember { mutableStateOf(draft.personality) }
    var background by remember { mutableStateOf(draft.background) }
    var examples by remember { mutableStateOf(draft.exampleDialogue) }
    var greeting by remember { mutableStateOf(draft.greeting) }
    var alternates by remember { mutableStateOf(draft.alternateGreetings.joinToString("\n")) }
    var instructions by remember { mutableStateOf(draft.extraInstructions) }
    var showHelp by remember { mutableStateOf(false) }
    val title = if (draft.type == ProfileType.CHARACTER) "角色設定" else "Persona 設定"
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { Back { if (draft.type == ProfileType.CHARACTER) viewModel.openCharacters() else viewModel.openLibrary() } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名稱") }, supportingText = { Text("例如：艾莉亞、本人、第三人稱旁白") })
            OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth(), label = { Text("簡介") }, minLines = 2)
            OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth(), label = { Text("個性") }, minLines = 3)
            OutlinedTextField(background, { background = it }, Modifier.fillMaxWidth(), label = { Text("背景") }, minLines = 4)
            OutlinedTextField(examples, { examples = it }, Modifier.fillMaxWidth(), label = { Text("範例對話") }, minLines = 3)
            OutlinedTextField(greeting, { greeting = it }, Modifier.fillMaxWidth(), label = { Text("開場白") }, minLines = 3)
            OutlinedTextField(alternates, { alternates = it }, Modifier.fillMaxWidth(), label = { Text("替代開場白") }, supportingText = { Text("每行一個替代版本") }, minLines = 2)
            OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text("額外指示") }, minLines = 2)
            TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "收合填寫示範" else "查看填寫示範") }
            if (showHelp) Card { Text("簡介：一名尋找失落城市的旅行學者。\n個性：冷靜、觀察敏銳，面對熟人會偶爾開玩笑。\n背景：曾在北方學院研究古代文字。\n範例對話：我不會急著下結論，先看看牆上的刻痕。\n開場白：你也注意到這扇門了嗎？", Modifier.padding(12.dp)) }
            Button(onClick = {
                viewModel.saveProfile(ProfileDraft(draft.id, draft.type, name, summary, personality, background, examples, greeting, alternates.lines().filter(String::isNotBlank), instructions))
            }, Modifier.fillMaxWidth()) { Text("儲存") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldSetsScreen(viewModel: MainViewModel) {
    val sets by viewModel.worldSets.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importDocument(it, ImportTarget.WORLD_SET) } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("世界設定集") }, navigationIcon = { Back(viewModel::openLibrary) }, actions = { IconButton(onClick = { launcher.launch(DOCUMENT_TYPES) }) { Icon(Icons.Default.UploadFile, "匯入世界設定") } }) },
        floatingActionButton = { FloatingActionButton(onClick = viewModel::newWorldSet) { Icon(Icons.Default.Add, "新增設定集") } },
    ) { padding ->
        if (sets.isEmpty()) EmptyState("還沒有世界設定集", "手動新增條目，或匯入文件讓 AI 拆成關鍵詞設定。", Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sets, key = { it.id }) { set ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.editWorldSet(set) }) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(set.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { viewModel.deleteWorldSet(set) }) { Icon(Icons.Default.Delete, "刪除") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldSetEditScreen(viewModel: MainViewModel) {
    val worldSet by viewModel.editingWorldSet.collectAsStateWithLifecycle()
    val entries by viewModel.editingWorldEntries.collectAsStateWithLifecycle()
    var name by remember(worldSet?.id) { mutableStateOf(worldSet?.name.orEmpty()) }
    var depth by remember(worldSet?.id) { mutableStateOf((worldSet?.scanDepth ?: 10).toString()) }
    var editingEntry by remember { mutableStateOf<WorldEntryEntity?>(null) }
    var showEntryDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("編輯世界設定集") }, navigationIcon = { Back(viewModel::openWorldSets) }) },
        floatingActionButton = {
            if (worldSet != null) FloatingActionButton(onClick = { editingEntry = null; showEntryDialog = true }) { Icon(Icons.Default.Add, "新增條目") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("設定集名稱") })
                OutlinedTextField(depth, { depth = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("掃描最近訊息數") })
                Button(onClick = { viewModel.saveWorldSet(name, depth.toIntOrNull() ?: 10) }, Modifier.fillMaxWidth()) { Text(if (worldSet == null) "建立設定集" else "儲存設定集") }
            }
            if (worldSet == null) item { Text("先建立設定集，就能新增關鍵詞條目。") }
            items(entries, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth().clickable { editingEntry = entry; showEntryDialog = true }) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(entry.title, fontWeight = FontWeight.Bold); Text(if (entry.alwaysInclude) "每次附加" else jsonStrings(entry.keywordsJson).joinToString(", "), style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { viewModel.deleteWorldEntry(entry) }) { Icon(Icons.Default.Delete, "刪除") }
                    }
                }
            }
        }
    }
    if (showEntryDialog) WorldEntryDialog(editingEntry, { showEntryDialog = false }) { id, title, keys, content, always, enabled ->
        viewModel.saveWorldEntry(id, title, keys, content, always, enabled); showEntryDialog = false
    }
}

@Composable
private fun WorldEntryDialog(entry: WorldEntryEntity?, onDismiss: () -> Unit, onSave: (String?, String, String, String, Boolean, Boolean) -> Unit) {
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var keys by remember(entry?.id) { mutableStateOf(entry?.let { jsonStrings(it.keywordsJson).joinToString(", ") }.orEmpty()) }
    var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    var always by remember(entry?.id) { mutableStateOf(entry?.alwaysInclude ?: false) }
    var enabled by remember(entry?.id) { mutableStateOf(entry?.enabled ?: true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("世界設定條目") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("標題") })
            OutlinedTextField(keys, { keys = it }, label = { Text("關鍵詞") }, supportingText = { Text("用逗號分隔") })
            OutlinedTextField(content, { content = it }, label = { Text("內容") }, minLines = 4)
            ToggleRow("每次都附加", always) { always = it }
            ToggleRow("啟用", enabled) { enabled = it }
        }
    }, confirmButton = { TextButton(onClick = { onSave(entry?.id, title, keys, content, always, enabled) }) { Text("儲存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatScreen(viewModel: MainViewModel) {
    val character by viewModel.newChatCharacter.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val sets by viewModel.worldSets.collectAsStateWithLifecycle()
    val personaId by viewModel.newChatPersonaId.collectAsStateWithLifecycle()
    val setIds by viewModel.newChatWorldSetIds.collectAsStateWithLifecycle()
    val greeting by viewModel.newChatGreeting.collectAsStateWithLifecycle()
    val greetings = remember(character) {
        buildList {
            character?.greeting?.takeIf(String::isNotBlank)?.let(::add)
            character?.let { addAll(jsonStrings(it.alternateGreetingsJson)) }
        }.distinct()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("開始新對話") }, navigationIcon = { Back(viewModel::openConversations) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("角色：${character?.name ?: "一般聊天"}", style = MaterialTheme.typography.titleMedium) }
            if (greetings.size > 1) {
                item { Text("選擇角色開場白", fontWeight = FontWeight.Bold) }
                items(greetings) { option -> SelectRow(option, greeting == option) { viewModel.selectNewChatGreeting(option) } }
            }
            item { Text("選擇 Persona（可略過）", fontWeight = FontWeight.Bold) }
            item { SelectRow("不指定 Persona", personaId == null) { viewModel.selectNewChatPersona(null) } }
            items(personas, key = { it.id }) { SelectRow(it.name, personaId == it.id) { viewModel.selectNewChatPersona(it.id) } }
            item { Text("啟用世界設定集（可複選）", Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold) }
            if (sets.isEmpty()) item { Text("尚未建立世界設定集。") }
            items(sets, key = { it.id }) { set -> CheckRow(set.name, set.id in setIds) { viewModel.toggleNewChatWorldSet(set.id) } }
            item { Button(viewModel::createConfiguredConversation, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("建立對話") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(viewModel: MainViewModel) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contexts by viewModel.generationContexts.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val contextMap = remember(contexts) { contexts.associate { it.messageId to jsonStrings(it.activatedWorldEntriesJson) } }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("AI Chat"); Text(settings.model, style = MaterialTheme.typography.labelSmall, maxLines = 1) } }, navigationIcon = { Back(viewModel::openConversations) }, actions = {
            IconButton(onClick = viewModel::openChatInfo) { Icon(Icons.Default.Info, "對話資訊") }
            IconButton(onClick = viewModel::openModels) { Icon(Icons.Default.Tune, "選擇模型") }
        }) },
        bottomBar = { MessageComposer(input, streaming, viewModel::setInput, viewModel::send, viewModel::stopStreaming) },
    ) { padding ->
        if (messages.isEmpty()) EmptyState("開始聊天", "輸入訊息，或從角色頁建立帶有開場白的對話。", Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages, key = { it.id }) { message -> MessageBubble(message, contextMap[message.id].orEmpty(), message.role == "assistant" && message.id == messages.lastOrNull()?.id, viewModel::retryLastResponse) }
        }
    }
}

@Composable
private fun MessageComposer(input: String, streaming: Boolean, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    Surface(shadowElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(input, onInput, Modifier.weight(1f), placeholder = { Text("輸入訊息") }, maxLines = 5)
        IconButton(onClick = if (streaming) onStop else onSend) { Icon(if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (streaming) "停止" else "送出") }
    } }
}

@Composable
private fun MessageBubble(message: MessageEntity, worldHits: List<String>, canRetry: Boolean, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(Modifier.fillMaxWidth(if (user) .86f else .96f), colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                if (message.content.isBlank()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else SelectionContainer { MarkdownText(message.content) }
                if (worldHits.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) { Text("世界設定命中 ${worldHits.size} 條") }
                    if (expanded) Text(worldHits.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                }
                if (message.content.isNotBlank()) Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) { Icon(Icons.Default.ContentCopy, "複製", Modifier.size(18.dp)) }
                    if (canRetry) IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重試", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInfoScreen(viewModel: MainViewModel) {
    val conversation by viewModel.selectedConversation.collectAsStateWithLifecycle()
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val sets by viewModel.worldSets.collectAsStateWithLifecycle()
    val activeIds by viewModel.activeWorldSetIds.collectAsStateWithLifecycle()
    val current = conversation ?: return
    var summary by remember(current.id, current.summary) { mutableStateOf(current.summary) }
    Scaffold(topBar = { TopAppBar(title = { Text("對話資訊") }, navigationIcon = { Back(viewModel::openCurrentChat) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Persona", fontWeight = FontWeight.Bold) }
            item { SelectRow("不指定 Persona", current.personaId == null) { viewModel.updateConversationPersona(null) } }
            items(personas, key = { it.id }) { persona -> SelectRow(persona.name, current.personaId == persona.id) { viewModel.updateConversationPersona(persona.id) } }
            item { Text("世界設定集", Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold) }
            items(sets, key = { it.id }) { set -> CheckRow(set.name, set.id in activeIds) { viewModel.toggleConversationWorldSet(set.id) } }
            item {
                OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("較早對話摘要") }, supportingText = { Text("上下文過長時由 AI 自動建立，也可以手動修正。") }, minLines = 5)
                Button(onClick = { viewModel.saveConversationSummary(summary) }, Modifier.fillMaxWidth()) { Text("儲存摘要") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(viewModel: MainViewModel, settings: AppSettings) {
    var provider by remember { mutableStateOf(settings.provider) }; var base by remember { mutableStateOf(settings.customBaseUrl) }
    var model by remember { mutableStateOf(settings.model) }; var key by remember { mutableStateOf("") }; var dark by remember { mutableStateOf(settings.darkTheme) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(provider) { if (provider != settings.provider) model = provider.defaultModel; key = "" }
    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }, bottomBar = { RootBottomBar(viewModel, Screen.SETTINGS) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box { OutlinedButton(onClick = { menu = true }) { Text(provider.label) }; DropdownMenu(menu, { menu = false }) { Provider.entries.forEach { option -> DropdownMenuItem({ Text(option.label) }, { provider = option; menu = false }) } } }
            if (provider == Provider.CUSTOM) OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, supportingText = { Text("HTTP 可以使用，但傳送前會警告可能外洩。") })
            else Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, placeholder = { Text(if (viewModel.currentApiKey(provider).isBlank()) "填入 API Key" else "已保存；留白可沿用") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型 ID") })
            ToggleRow("深色模式", dark) { dark = it }
            Button(onClick = { viewModel.saveSettings(provider, base, model, key, dark) }, Modifier.fillMaxWidth()) { Text("儲存設定") }
            Text("API Key 使用 Android Keystore 保護。App 不會自動備份本機內容。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsScreen(viewModel: MainViewModel, selected: String) {
    val models by viewModel.models.collectAsStateWithLifecycle(); val loading by viewModel.isLoadingModels.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }; val filtered = remember(models, query) { filterModels(models, query) }
    Scaffold(topBar = { TopAppBar(title = { Text("選擇模型") }, navigationIcon = { Back(viewModel::openCurrentChat) }, actions = { IconButton(onClick = viewModel::refreshModels) { Icon(Icons.Default.Refresh, "重新載入") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (models.isNotEmpty()) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), placeholder = { Text("搜尋模型") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除") } })
            when { loading -> LoadingOverlay("載入模型..."); models.isEmpty() -> EmptyState("尚未取得模型", "可重新載入，或在設定頁手動填寫模型 ID。"); filtered.isEmpty() -> EmptyState("找不到符合的模型", "清除搜尋文字後再試一次。"); else -> LazyColumn { items(filtered, key = { it }) { model -> Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clickable { viewModel.chooseModel(model) }) { Row(Modifier.fillMaxWidth().padding(14.dp)) { Text(model, Modifier.weight(1f)); if (model == selected) Icon(Icons.Default.Check, "目前模型") } } } } }
        }
    }
}

@Composable private fun SelectRow(label: String, selected: Boolean, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(selected, { onClick() }); Text(label) }
@Composable private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { onClick() }); Text(label) }
@Composable private fun ToggleRow(label: String, checked: Boolean, onCheck: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onCheck) }
@Composable private fun Back(onClick: () -> Unit) = IconButton(onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
@Composable private fun LoadingOverlay(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(text) } } }
@Composable private fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail) } }

@Composable
private fun MarkdownText(markdown: String) {
    val blocks = markdown.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { blocks.forEachIndexed { index, block ->
        if (index % 2 == 1) Surface(color = MaterialTheme.colorScheme.inverseSurface) { Text(block.substringAfter('\n', block).trimEnd(), Modifier.fillMaxWidth().padding(10.dp), color = MaterialTheme.colorScheme.inverseOnSurface, fontFamily = FontFamily.Monospace) }
        else if (block.isNotBlank()) Text(inlineMarkdown(block.trim()))
    } }
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) when {
        text.startsWith("**", index) -> { val end = text.indexOf("**", index + 2); if (end > index) { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(text.substring(index + 2, end)); pop(); index = end + 2 } else { append("**"); index += 2 } }
        text[index] == '`' -> { val end = text.indexOf('`', index + 1); if (end > index) { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace)); append(text.substring(index + 1, end)); pop(); index = end + 1 } else { append('`'); index++ } }
        else -> { append(text[index]); index++ }
    }
}

@Composable
private fun ErrorDialog(error: UiError, onDismiss: () -> Unit, onSettings: () -> Unit, onTrim: () -> Unit, onNew: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(error.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(error.message); Text(error.suggestion, fontWeight = FontWeight.Bold) } }, confirmButton = {
        if (error.kind == ErrorKind.CONTEXT_LENGTH) TextButton(onClick = onTrim) { Text("裁切舊訊息並重試") } else TextButton(onClick = onDismiss) { Text("關閉") }
    }, dismissButton = { if (error.kind == ErrorKind.CONTEXT_LENGTH) TextButton(onClick = onNew) { Text("建立新對話") } else TextButton(onClick = onSettings) { Text("前往設定") } })
}
