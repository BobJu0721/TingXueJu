package com.aichat.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.ProfileType
import com.aichat.app.data.Provider
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

private val DOCUMENT_TYPES = arrayOf(
    "text/plain",
    "application/json",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/octet-stream",
)

private val ROOT_SCREENS = setOf(Screen.CONVERSATIONS, Screen.CHARACTERS, Screen.LIBRARY, Screen.SETTINGS)

private object AppRoute {
    const val HOME = "home"
    const val CHAT = "chat"
    const val MODELS = "models"
    const val PROFILE_EDIT = "profile_edit"
    const val WORLD_SETS = "world_sets"
    const val WORLD_SET_EDIT = "world_set_edit"
    const val NEW_CHAT = "new_chat"
    const val CHAT_INFO = "chat_info"
}

@Composable
fun AIChatApp(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val navigationVersion by viewModel.navigationVersion.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val showUnsafeWarning by viewModel.showUnsafeHttpWarning.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val language = settings.language
    val navController = rememberNavController()
    var selectedRoot by remember { mutableStateOf(Screen.CONVERSATIONS) }
    LaunchedEffect(notice) { if (notice != null) viewModel.clearNotice() }

    LaunchedEffect(screen, navigationVersion) {
        when (screen) {
            in ROOT_SCREENS -> {
                selectedRoot = screen
                navController.navigate(AppRoute.HOME) {
                    popUpTo(AppRoute.HOME)
                    launchSingleTop = true
                }
            }
            Screen.CHAT -> {
                if (!navController.popBackStack(AppRoute.CHAT, inclusive = false)) {
                    navController.navigate(AppRoute.CHAT) { launchSingleTop = true }
                }
            }
            Screen.MODELS -> navController.navigate(AppRoute.MODELS) { launchSingleTop = true }
            Screen.PROFILE_EDIT -> navController.navigate(AppRoute.PROFILE_EDIT) { launchSingleTop = true }
            Screen.WORLD_SETS -> navController.navigate(AppRoute.WORLD_SETS) { launchSingleTop = true }
            Screen.WORLD_SET_EDIT -> navController.navigate(AppRoute.WORLD_SET_EDIT) { launchSingleTop = true }
            Screen.NEW_CHAT -> navController.navigate(AppRoute.NEW_CHAT) { launchSingleTop = true }
            Screen.CHAT_INFO -> navController.navigate(AppRoute.CHAT_INFO) { launchSingleTop = true }
            else -> Unit
        }
    }

    MaterialTheme(colorScheme = if (settings.darkTheme) darkColorScheme() else lightColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = AppRoute.HOME) {
                composable(AppRoute.HOME) {
                    RootPager(viewModel, selectedRoot, settings, language) { selectedRoot = it }
                }
                composable(AppRoute.CHAT) { ChatScreen(viewModel, language) }
                composable(AppRoute.MODELS) { ModelsScreen(viewModel, settings.model, language) }
                composable(AppRoute.PROFILE_EDIT) { ProfileEditScreen(viewModel, language) }
                composable(AppRoute.WORLD_SETS) { WorldSetsScreen(viewModel, language) }
                composable(AppRoute.WORLD_SET_EDIT) { WorldSetEditScreen(viewModel, language) }
                composable(AppRoute.NEW_CHAT) { NewChatScreen(viewModel, language) }
                composable(AppRoute.CHAT_INFO) { ChatInfoScreen(viewModel, language) }
            }
            if (isImporting) {
                LoadingOverlay(language.pick("AI 正在整理文件...", "AI 正在整理文件..."))
            }
        }
        error?.let { current ->
            ErrorDialog(current, language, viewModel::clearError, viewModel::openSettings, viewModel::trimOldestContextAndRetry) {
                viewModel.clearError(); viewModel.beginNewChat()
            }
        }
        pendingImport?.let { pending ->
            AlertDialog(
                onDismissRequest = viewModel::dismissPendingImport,
                title = { Text(language.pick("確認傳送文件", "确认发送文件")) },
                text = {
                    Text(language.pick(
                        "將把「${pending.document.name}」的 ${pending.document.text.length} 個字元傳送給 ${settings.provider.label} / ${settings.model}，預估最多 ${pending.estimatedCalls} 次 API 呼叫。請確認文件不含不希望傳出的私人內容。",
                        "会把「${pending.document.name}」的 ${pending.document.text.length} 个字符发送给 ${settings.provider.label} / ${settings.model}，预计最多 ${pending.estimatedCalls} 次 API 调用。请确认文件不含不希望传出的私人内容。",
                    ))
                },
                confirmButton = { TextButton(onClick = viewModel::confirmPendingImport) { Text(language.pick("同意並整理", "同意并整理")) } },
                dismissButton = { TextButton(onClick = viewModel::dismissPendingImport) { Text(language.pick("取消", "取消")) } },
            )
        }
        if (showUnsafeWarning) {
            AlertDialog(
                onDismissRequest = viewModel::dismissUnsafeHttp,
                title = { Text(language.pick("HTTP 端點警告", "HTTP 端点警告")) },
                text = { Text(language.pick("目前端點使用未加密 HTTP。API Key 和聊天內容可能外洩，確定仍要傳送嗎？", "目前端点使用未加密 HTTP。API Key 和聊天内容可能外泄，确定仍要发送吗？")) },
                confirmButton = { TextButton(onClick = viewModel::confirmUnsafeHttp) { Text(language.pick("仍要傳送", "仍要发送")) } },
                dismissButton = { TextButton(onClick = viewModel::dismissUnsafeHttp) { Text(language.pick("取消", "取消")) } },
            )
        }
    }
}

@Composable
private fun RootPager(
    viewModel: MainViewModel,
    selected: Screen,
    settings: AppSettings,
    language: AppLanguage,
    onRootSelected: (Screen) -> Unit,
) {
    val roots = listOf(Screen.CONVERSATIONS, Screen.CHARACTERS, Screen.LIBRARY, Screen.SETTINGS)
    val selectedPage = roots.indexOf(selected).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedPage) { roots.size }
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    var programmaticTargetPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedPage) {
        if (pagerState.currentPage != selectedPage) {
            programmaticTargetPage = selectedPage
            pagerState.animateScrollToPage(selectedPage)
            programmaticTargetPage = null
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to programmaticTargetPage }.collect { (page, targetPage) ->
            if (targetPage != null) return@collect
            val target = roots[page]
            if (target != currentSelected) onRootSelected(target)
        }
    }
    Scaffold(
        bottomBar = {
            RootBottomBar(viewModel, selected, language) { target ->
                val targetPage = roots.indexOf(target)
                if (targetPage < 0) return@RootBottomBar
                scope.launch {
                    if (pagerState.currentPage != targetPage) pagerState.animateScrollToPage(targetPage)
                    onRootSelected(target)
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            when (roots[page]) {
                Screen.CONVERSATIONS -> ConversationsScreen(viewModel, language, showBottomBar = false)
                Screen.CHARACTERS -> ProfilesScreen(viewModel, ProfileType.CHARACTER, language, showBottomBar = false)
                Screen.LIBRARY -> LibraryScreen(viewModel, language, showBottomBar = false)
                Screen.SETTINGS -> SettingsScreen(viewModel, settings, showBottomBar = false)
                else -> Unit
            }
        }
    }
}

@Composable
private fun RootBottomBar(
    viewModel: MainViewModel,
    selected: Screen,
    language: AppLanguage,
    onSelect: ((Screen) -> Unit)? = null,
) {
    fun select(screen: Screen) {
        if (onSelect != null) onSelect(screen) else viewModel.openRootScreen(screen)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactBottomItem(language.pick("對話", "对话"), Icons.Default.Chat, selected == Screen.CONVERSATIONS) { select(Screen.CONVERSATIONS) }
            CompactBottomItem(language.pick("角色", "角色"), Icons.Default.Person, selected == Screen.CHARACTERS) { select(Screen.CHARACTERS) }
            CompactBottomItem(language.pick("資料庫", "资料库"), Icons.Default.Storage, selected == Screen.LIBRARY) { select(Screen.LIBRARY) }
            CompactBottomItem(language.pick("設定", "设置"), Icons.Default.Settings, selected == Screen.SETTINGS) { select(Screen.SETTINGS) }
        }
    }
}

@Composable
private fun CompactTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(Modifier.fillMaxWidth().statusBarsPadding(), shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) navigationIcon() else Spacer(Modifier.width(8.dp))
            val titleModifier = if (onTitleClick != null) Modifier.weight(1f).clickable(onClick = onTitleClick) else Modifier.weight(1f)
            Column(titleModifier, verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
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

@Composable
private fun ConversationsScreen(viewModel: MainViewModel, language: AppLanguage, showBottomBar: Boolean = true) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { CompactTopBar(language.pick("聽雪居", "听雪居")) },
        bottomBar = { if (showBottomBar) RootBottomBar(viewModel, Screen.CONVERSATIONS, language) },
        floatingActionButton = { FloatingActionButton(onClick = { viewModel.beginNewChat() }) { Icon(Icons.Default.Add, language.pick("新增對話", "新增对话")) } },
    ) { padding ->
        if (conversations.isEmpty()) EmptyState(language.pick("還沒有對話", "还没有对话"), language.pick("按右下角新增一般對話，或從角色頁開始劇情。", "按右下角新增一般对话，或从角色页开始剧情。"), Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.id }) { conversation ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.selectConversation(conversation.id) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(conversation.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { viewModel.deleteConversation(conversation) }) { Icon(Icons.Default.Delete, language.pick("刪除對話", "删除对话")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilesScreen(viewModel: MainViewModel, type: ProfileType, language: AppLanguage, showBottomBar: Boolean = true) {
    val profiles by (if (type == ProfileType.CHARACTER) viewModel.characters else viewModel.personas).collectAsStateWithLifecycle()
    val importTarget = if (type == ProfileType.CHARACTER) ImportTarget.CHARACTER else ImportTarget.PERSONA
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importDocument(it, importTarget) } }
    val title = if (type == ProfileType.CHARACTER) language.pick("角色", "角色") else "Persona"
    Scaffold(
        topBar = { CompactTopBar(title, actions = { IconButton(onClick = { launcher.launch(DOCUMENT_TYPES) }) { Icon(Icons.Default.UploadFile, language.pick("匯入文件", "导入文件")) } }) },
        bottomBar = { if (showBottomBar && type == ProfileType.CHARACTER) RootBottomBar(viewModel, Screen.CHARACTERS, language) },
        floatingActionButton = { FloatingActionButton(onClick = { viewModel.newProfile(type) }) { Icon(Icons.Default.Add, language.pick("新增$title", "新增$title")) } },
    ) { padding ->
        if (profiles.isEmpty()) EmptyState(language.pick("還沒有$title", "还没有$title"), language.pick("可以手動建立，或從 TXT、JSON、DOCX 文件交給 AI 整理。", "可以手动建立，或从 TXT、JSON、DOCX 文件交给 AI 整理。"), Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles, key = { it.id }) { profile -> ProfileRow(profile, type == ProfileType.CHARACTER, viewModel, language) }
        }
    }
}

@Composable
private fun ProfileRow(profile: ProfileEntity, canChat: Boolean, viewModel: MainViewModel, language: AppLanguage) {
    Card(Modifier.fillMaxWidth().clickable { viewModel.editProfile(profile) }) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold)
                if (profile.summary.isNotBlank()) Text(profile.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (canChat) IconButton(onClick = { viewModel.beginNewChat(profile.id) }) { Icon(Icons.Default.Chat, language.pick("開始聊天", "开始聊天")) }
            IconButton(onClick = { viewModel.editProfile(profile) }) { Icon(Icons.Default.Edit, language.pick("編輯", "编辑")) }
            IconButton(onClick = { viewModel.deleteProfile(profile) }) { Icon(Icons.Default.Delete, language.pick("刪除", "删除")) }
        }
    }
}

@Composable
private fun LibraryScreen(viewModel: MainViewModel, language: AppLanguage, showBottomBar: Boolean = true) {
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importDocument(it, ImportTarget.PERSONA) } }
    Scaffold(topBar = { CompactTopBar(language.pick("資料庫", "资料库")) }, bottomBar = { if (showBottomBar) RootBottomBar(viewModel, Screen.LIBRARY, language) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = viewModel::openWorldSets)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MenuBook, null); Spacer(Modifier.width(12.dp))
                        Column { Text(language.pick("世界設定集", "世界设定集"), fontWeight = FontWeight.Bold); Text(language.pick("地點、人物關係與規則", "地点、人物关系与规则"), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Persona", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { launcher.launch(DOCUMENT_TYPES) }) { Icon(Icons.Default.UploadFile, language.pick("匯入 Persona", "导入 Persona")) }
                    IconButton(onClick = { viewModel.newProfile(ProfileType.PERSONA) }) { Icon(Icons.Default.Add, language.pick("新增 Persona", "新增 Persona")) }
                }
            }
            if (personas.isEmpty()) item { Text(language.pick("尚未建立 Persona。你仍然可以不指定身份直接聊天。", "尚未建立 Persona。你仍然可以不指定身份直接聊天。")) }
            items(personas, key = { it.id }) { ProfileRow(it, false, viewModel, language) }
        }
    }
}

@Composable
private fun ProfileEditScreen(viewModel: MainViewModel, language: AppLanguage) {
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
    val title = if (draft.type == ProfileType.CHARACTER) language.pick("角色設定", "角色设置") else language.pick("Persona 設定", "Persona 设置")
    Scaffold(topBar = { CompactTopBar(title, navigationIcon = { Back(language) { if (draft.type == ProfileType.CHARACTER) viewModel.openCharacters() else viewModel.openLibrary() } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("名稱", "名称")) }, supportingText = { Text(language.pick("例如：艾莉亞、本人、第三人稱旁白", "例如：艾莉亚、本人、第三人称旁白")) })
            OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("簡介", "简介")) }, minLines = 2)
            OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("個性", "个性")) }, minLines = 3)
            OutlinedTextField(background, { background = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("背景", "背景")) }, minLines = 4)
            OutlinedTextField(examples, { examples = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("範例對話", "范例对话")) }, minLines = 3)
            OutlinedTextField(greeting, { greeting = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("開場白", "开场白")) }, minLines = 3)
            OutlinedTextField(alternates, { alternates = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("替代開場白", "替代开场白")) }, supportingText = { Text(language.pick("每行一個替代版本", "每行一个替代版本")) }, minLines = 2)
            OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("額外指示", "额外指示")) }, minLines = 2)
            TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) language.pick("收合填寫示範", "收合填写示范") else language.pick("查看填寫示範", "查看填写示范")) }
            if (showHelp) Card { Text(language.pick("簡介：一名尋找失落城市的旅行學者。\n個性：冷靜、觀察敏銳，面對熟人會偶爾開玩笑。\n背景：曾在北方學院研究古代文字。\n範例對話：我不會急著下結論，先看看牆上的刻痕。\n開場白：你也注意到這扇門了嗎？", "简介：一名寻找失落城市的旅行学者。\n个性：冷静、观察敏锐，面对熟人会偶尔开玩笑。\n背景：曾在北方学院研究古代文字。\n范例对话：我不会急着下结论，先看看墙上的刻痕。\n开场白：你也注意到这扇门了吗？"), Modifier.padding(12.dp)) }
            Button(onClick = {
                viewModel.saveProfile(ProfileDraft(draft.id, draft.type, name, summary, personality, background, examples, greeting, alternates.lines().filter(String::isNotBlank), instructions))
            }, Modifier.fillMaxWidth()) { Text(language.pick("儲存", "保存")) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WorldSetsScreen(viewModel: MainViewModel, language: AppLanguage) {
    val sets by viewModel.worldSets.collectAsStateWithLifecycle()
    val templates = viewModel.worldTemplates
    var showTemplates by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importDocument(it, ImportTarget.WORLD_SET) } }
    Scaffold(
        topBar = {
            CompactTopBar(
                language.pick("世界設定集", "世界设定集"),
                navigationIcon = { Back(language, viewModel::openLibrary) },
                actions = {
                    TextButton(onClick = { showTemplates = true }) { Text(language.pick("模板", "模板")) }
                    IconButton(onClick = { launcher.launch(DOCUMENT_TYPES) }) { Icon(Icons.Default.UploadFile, language.pick("匯入世界設定", "导入世界设定")) }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = viewModel::newWorldSet) { Icon(Icons.Default.Add, language.pick("新增設定集", "新增设定集")) } },
    ) { padding ->
        if (sets.isEmpty()) EmptyState(language.pick("還沒有世界設定集", "还没有世界设定集"), language.pick("可使用模板、手動新增條目，或匯入文件讓 AI 拆成關鍵詞設定。", "可使用模板、手动新增条目，或导入文件让 AI 拆成关键词设定。"), Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sets, key = { it.id }) { set ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.editWorldSet(set) }) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(set.name, fontWeight = FontWeight.Bold)
                            if (set.overview.isNotBlank()) Text(set.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { viewModel.deleteWorldSet(set) }) { Icon(Icons.Default.Delete, language.pick("刪除", "删除")) }
                    }
                }
            }
        }
    }
    if (showTemplates) {
        AlertDialog(
            onDismissRequest = { showTemplates = false },
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
                        ) { Text(template.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTemplates = false }) { Text(language.pick("取消", "取消")) } },
        )
    }
}

@Composable
private fun WorldSetEditScreen(viewModel: MainViewModel, language: AppLanguage) {
    val worldSet by viewModel.editingWorldSet.collectAsStateWithLifecycle()
    val entries by viewModel.editingWorldEntries.collectAsStateWithLifecycle()
    var name by remember(worldSet?.id) { mutableStateOf(worldSet?.name.orEmpty()) }
    var overview by remember(worldSet?.id) { mutableStateOf(worldSet?.overview.orEmpty()) }
    var depth by remember(worldSet?.id) { mutableStateOf((worldSet?.scanDepth ?: 10).toString()) }
    var editingEntry by remember { mutableStateOf<WorldEntryEntity?>(null) }
    var showEntryDialog by remember { mutableStateOf(false) }
    LaunchedEffect(worldSet?.id, name, overview, depth) {
        val current = worldSet ?: return@LaunchedEffect
        val scanDepth = depth.toIntOrNull() ?: current.scanDepth
        if (
            name.trim() == current.name &&
            overview.trim() == current.overview &&
            scanDepth.coerceIn(1, 100) == current.scanDepth
        ) return@LaunchedEffect
        delay(600)
        viewModel.updateWorldSetMetadata(name, overview, scanDepth)
    }
    Scaffold(
        topBar = { CompactTopBar(language.pick("編輯世界設定集", "编辑世界设定集"), navigationIcon = { Back(language, viewModel::openWorldSets) }) },
        floatingActionButton = {
            if (worldSet != null) FloatingActionButton(onClick = { editingEntry = null; showEntryDialog = true }) { Icon(Icons.Default.Add, language.pick("新增條目", "新增条目")) }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                OutlinedTextField(depth, { depth = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(language.pick("掃描最近訊息數", "扫描最近消息数")) })
            }
            if (worldSet == null) item { Text(language.pick("正在建立設定集...", "正在建立设定集...")) }
            items(entries, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth().clickable { editingEntry = entry; showEntryDialog = true }) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(entry.title, fontWeight = FontWeight.Bold); Text(if (entry.alwaysInclude) language.pick("每次附加", "每次附加") else jsonStrings(entry.keywordsJson).joinToString(", "), style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { viewModel.deleteWorldEntry(entry) }) { Icon(Icons.Default.Delete, language.pick("刪除", "删除")) }
                    }
                }
            }
        }
    }
    if (showEntryDialog) WorldEntryDialog(editingEntry, language, { showEntryDialog = false }) { id, title, keys, content, always, enabled ->
        viewModel.saveWorldEntry(id, title, keys, content, always, enabled); showEntryDialog = false
    }
}

@Composable
private fun WorldEntryDialog(entry: WorldEntryEntity?, language: AppLanguage, onDismiss: () -> Unit, onSave: (String?, String, String, String, Boolean, Boolean) -> Unit) {
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var keys by remember(entry?.id) { mutableStateOf(entry?.let { jsonStrings(it.keywordsJson).joinToString(", ") }.orEmpty()) }
    var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    var always by remember(entry?.id) { mutableStateOf(entry?.alwaysInclude ?: false) }
    var enabled by remember(entry?.id) { mutableStateOf(entry?.enabled ?: true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(language.pick("世界設定條目", "世界设定条目")) }, text = {
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
    }, confirmButton = { TextButton(onClick = { onSave(entry?.id, title, keys, content, always, enabled) }) { Text(language.pick("儲存", "保存")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(language.pick("取消", "取消")) } })
}

@Composable
private fun NewChatScreen(viewModel: MainViewModel, language: AppLanguage) {
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
    Scaffold(topBar = { CompactTopBar(language.pick("開始新對話", "开始新对话"), navigationIcon = { Back(language, viewModel::openConversations) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(language.pick("角色：${character?.name ?: "一般聊天"}", "角色：${character?.name ?: "一般聊天"}"), style = MaterialTheme.typography.titleMedium) }
            if (greetings.size > 1) {
                item { Text(language.pick("選擇角色開場白", "选择角色开场白"), fontWeight = FontWeight.Bold) }
                items(greetings) { option -> SelectRow(option, greeting == option) { viewModel.selectNewChatGreeting(option) } }
            }
            item { Text(language.pick("選擇 Persona（可略過）", "选择 Persona（可略过）"), fontWeight = FontWeight.Bold) }
            item { SelectRow(language.pick("不指定 Persona", "不指定 Persona"), personaId == null) { viewModel.selectNewChatPersona(null) } }
            items(personas, key = { it.id }) { SelectRow(it.name, personaId == it.id) { viewModel.selectNewChatPersona(it.id) } }
            item { Text(language.pick("啟用世界設定集（可複選）", "启用世界设定集（可复选）"), Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold) }
            if (sets.isEmpty()) item { Text(language.pick("尚未建立世界設定集。", "尚未建立世界设定集。")) }
            items(sets, key = { it.id }) { set -> CheckRow(set.name, set.id in setIds) { viewModel.toggleNewChatWorldSet(set.id) } }
            item { Button(viewModel::createConfiguredConversation, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(language.pick("建立對話", "建立对话")) } }
        }
    }
}

@Composable
private fun ChatScreen(viewModel: MainViewModel, language: AppLanguage) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contexts by viewModel.generationContexts.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val conversation by viewModel.selectedConversation.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedConversationId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    var lastOpenedId by remember { mutableStateOf<String?>(null) }
    var autoFollow by remember { mutableStateOf(true) }
    var showScrollToBottom by remember { mutableStateOf(false) }
    var actionMessageId by remember(selectedId) { mutableStateOf<String?>(null) }
    var renameDialogVisible by remember(selectedId) { mutableStateOf(false) }
    var renameText by remember(selectedId, conversation?.title) { mutableStateOf(conversation?.title.orEmpty()) }
    val contextMap = remember(contexts) { contexts.associate { it.messageId to jsonStrings(it.activatedWorldEntriesJson) } }
    LaunchedEffect(listState, messages.size) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (_, _, isScrolling) ->
            if (isScrolling) autoFollow = listState.isNearBottom(messages.lastIndex)
        }
    }
    LaunchedEffect(listState, messages.size) {
        snapshotFlow { messages.isNotEmpty() && !listState.isNearBottom(messages.lastIndex) }
            .distinctUntilChanged()
            .collect { showScrollToBottom = it }
    }
    LaunchedEffect(selectedId, messages.size) {
        if (selectedId != null && selectedId != lastOpenedId && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
            autoFollow = true
            lastOpenedId = selectedId
        }
    }
    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.content, autoFollow) {
        if (messages.isNotEmpty() && autoFollow) listState.scrollToItem(messages.lastIndex)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            CompactTopBar(
                title = conversation?.title?.ifBlank { null } ?: language.pick("聽雪居", "听雪居"),
                subtitle = settings.model,
                navigationIcon = { Back(language, viewModel::openConversations) },
                onTitleClick = {
                    renameText = conversation?.title.orEmpty()
                    renameDialogVisible = conversation != null
                },
                actions = {
                    IconButton(onClick = viewModel::openChatInfo) { Icon(Icons.Default.Info, language.pick("對話資訊", "对话信息")) }
                    IconButton(onClick = viewModel::openModels) { Icon(Icons.Default.Tune, language.pick("選擇模型", "选择模型")) }
                },
            )
        },
        bottomBar = { MessageComposer(input, streaming, language, viewModel::setInput, viewModel::send, viewModel::stopStreaming) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ChatBackground(conversation?.backgroundImagePath.orEmpty(), settings.darkTheme)
            if (messages.isEmpty()) EmptyState(language.pick("開始聊天", "开始聊天"), language.pick("輸入訊息，或從角色頁建立帶有開場白的對話。", "输入消息，或从角色页建立带有开场白的对话。"))
            else LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        worldHits = contextMap[message.id].orEmpty(),
                        language = language,
                        bubbleOpacity = conversation?.messageBubbleOpacity ?: 1f,
                        actionsVisible = actionMessageId == message.id,
                        onToggleActions = {
                            actionMessageId = if (actionMessageId == message.id) null else message.id
                        },
                        onEdit = viewModel::editMessage,
                        onResend = {
                            actionMessageId = null
                            viewModel.resendFromMessage(it)
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        val targetIndex = messages.lastIndex
                        if (targetIndex >= 0) {
                            actionMessageId = null
                            autoFollow = true
                            chatScope.launch { listState.animateScrollToItem(targetIndex) }
                        }
                    },
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, language.pick("回到底部", "回到底部"))
                }
            }
        }
    }
    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text(language.pick("重新命名對話", "重新命名对话")) },
            text = {
                OutlinedTextField(
                    renameText,
                    { renameText = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(language.pick("對話名稱", "对话名称")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameConversation(renameText)
                        renameDialogVisible = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text(language.pick("儲存", "保存")) }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) { Text(language.pick("取消", "取消")) }
            },
        )
    }
}

@Composable
private fun MessageComposer(input: String, streaming: Boolean, language: AppLanguage, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(Modifier.imePadding(), shadowElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(input, onInput, Modifier.weight(1f), placeholder = { Text(language.pick("輸入訊息", "输入消息")) }, maxLines = 5)
        IconButton(onClick = {
            if (streaming) onStop()
            else {
                onSend()
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }) { Icon(if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (streaming) language.pick("停止", "停止") else language.pick("送出", "发送")) }
    } }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageBubble(
    message: MessageEntity,
    worldHits: List<String>,
    language: AppLanguage,
    bubbleOpacity: Float,
    actionsVisible: Boolean,
    onToggleActions: () -> Unit,
    onEdit: (String, String) -> Unit,
    onResend: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember(message.id, message.content) { mutableStateOf(message.content) }
    val user = message.role == "user"
    val canShowActions = message.content.isNotBlank()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(actionsVisible, canShowActions) {
        if (actionsVisible && canShowActions) {
            yield()
            bringIntoViewRequester.bringIntoView()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        val bubbleColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
        Card(
            Modifier
                .fillMaxWidth(if (user) .86f else .96f)
                .clickable(enabled = canShowActions, onClick = onToggleActions),
            colors = CardDefaults.cardColors(containerColor = bubbleColor.copy(alpha = bubbleOpacity.coerceIn(0.35f, 1f))),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (message.content.isBlank()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else SelectionContainer { MarkdownText(message.content) }
                if (worldHits.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) { Text(language.pick("世界設定命中 ${worldHits.size} 條", "世界设定命中 ${worldHits.size} 条")) }
                    if (expanded) Text(worldHits.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (canShowActions && actionsVisible) {
            Row(Modifier.padding(top = 2.dp)) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) { Icon(Icons.Default.ContentCopy, language.pick("複製", "复制"), Modifier.size(18.dp)) }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, language.pick("編輯", "编辑"), Modifier.size(18.dp)) }
                IconButton(onClick = { onResend(message.id) }) { Icon(Icons.Default.Refresh, language.pick("重新發送", "重新发送"), Modifier.size(18.dp)) }
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (user) language.pick("編輯自己的訊息", "编辑自己的消息") else language.pick("編輯 AI 訊息", "编辑 AI 消息")) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 12,
                    label = { Text(language.pick("訊息內容", "消息内容")) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(message.id, editText)
                    editing = false
                }) { Text(language.pick("儲存", "保存")) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(language.pick("取消", "取消")) } },
        )
    }
}

@Composable
private fun ChatInfoScreen(viewModel: MainViewModel, language: AppLanguage) {
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
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(language.pick("聊天背景", "聊天背景"), fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { backgroundLauncher.launch(arrayOf("image/*")) }, Modifier.weight(1f)) { Text(language.pick("上傳背景圖", "上传背景图")) }
                    if (current.backgroundImagePath.isNotBlank()) {
                        OutlinedButton(onClick = viewModel::clearConversationBackground, Modifier.weight(1f)) { Text(language.pick("移除背景", "移除背景")) }
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
                    onValueChangeFinished = {
                        viewModel.updateMessageBubbleOpacity(1f - bubbleTransparency)
                    },
                )
                Text(
                    language.pick("越高越透明，最低仍保留可讀性。", "越高越透明，最低仍保留可读性。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { Text("Persona", fontWeight = FontWeight.Bold) }
            item { SelectRow(language.pick("不指定 Persona", "不指定 Persona"), current.personaId == null) { viewModel.updateConversationPersona(null) } }
            items(personas, key = { it.id }) { persona -> SelectRow(persona.name, current.personaId == persona.id) { viewModel.updateConversationPersona(persona.id) } }
            item { Text(language.pick("世界設定集", "世界设定集"), Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold) }
            item {
                Text(
                    if (activeIds.isEmpty()) language.pick("目前未啟用世界設定集。", "目前未启用世界设定集。")
                    else language.pick("已啟用 ${activeIds.size} 個世界設定集。", "已启用 ${activeIds.size} 个世界设定集。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(sets, key = { it.id }) { set ->
                val count = countMap[set.id] ?: 0
                CheckRow(
                    language.pick("${set.name}（$count 條）", "${set.name}（$count 条）"),
                    set.id in activeIds,
                ) { viewModel.toggleConversationWorldSet(set.id) }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            OutlinedButton(onClick = { summaryModeMenu = true }, Modifier.fillMaxWidth()) {
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
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel, settings: AppSettings, showBottomBar: Boolean = true) {
    var provider by remember { mutableStateOf(settings.provider) }; var base by remember { mutableStateOf(settings.customBaseUrl) }
    var model by remember { mutableStateOf(settings.model) }; var key by remember { mutableStateOf("") }; var dark by remember { mutableStateOf(settings.darkTheme) }
    var language by remember(settings.language) { mutableStateOf(settings.language) }
    var menu by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    val lang = settings.language
    LaunchedEffect(provider) { if (provider != settings.provider) model = provider.defaultModel; key = "" }
    Scaffold(topBar = { CompactTopBar(lang.pick("設定", "设置")) }, bottomBar = { if (showBottomBar) RootBottomBar(viewModel, Screen.SETTINGS, lang) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(lang.pick("供應商", "供应商"), fontWeight = FontWeight.Bold)
            Box { OutlinedButton(onClick = { menu = true }) { Text(if (provider == Provider.CUSTOM) lang.pick("自訂端點", "自定义端点") else provider.label) }; DropdownMenu(menu, { menu = false }) { Provider.entries.forEach { option -> DropdownMenuItem({ Text(if (option == Provider.CUSTOM) lang.pick("自訂端點", "自定义端点") else option.label) }, { provider = option; menu = false }) } } }
            if (provider == Provider.CUSTOM) OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, supportingText = { Text(lang.pick("HTTP 可以使用，但傳送前會警告可能外洩。", "HTTP 可以使用，但发送前会警告可能外泄。")) })
            else Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, placeholder = { Text(if (viewModel.currentApiKey(provider).isBlank()) lang.pick("填入 API Key", "填入 API Key") else lang.pick("已保存；留白可沿用", "已保存；留白可沿用")) }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(lang.pick("模型 ID", "模型 ID")) })
            Text(lang.pick("介面語言", "界面语言"), fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { languageMenu = true }) { Text(language.label) }
                DropdownMenu(languageMenu, { languageMenu = false }) {
                    AppLanguage.entries.forEach { option ->
                        DropdownMenuItem({ Text(option.label) }, { language = option; languageMenu = false })
                    }
                }
            }
            ToggleRow(lang.pick("深色模式", "深色模式"), dark) { dark = it }
            Button(onClick = { viewModel.saveSettings(provider, base, model, key, dark, language) }, Modifier.fillMaxWidth()) { Text(lang.pick("儲存設定", "保存设置")) }
            Text(lang.pick("API Key 使用 Android Keystore 保護。App 不會自動備份本機內容。", "API Key 使用 Android Keystore 保护。App 不会自动备份本机内容。"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelsScreen(viewModel: MainViewModel, selected: String, language: AppLanguage) {
    val models by viewModel.models.collectAsStateWithLifecycle(); val loading by viewModel.isLoadingModels.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }; val filtered = remember(models, query) { filterModels(models, query) }
    Scaffold(topBar = { CompactTopBar(language.pick("選擇模型", "选择模型"), navigationIcon = { Back(language, viewModel::openCurrentChat) }, actions = { IconButton(onClick = viewModel::refreshModels) { Icon(Icons.Default.Refresh, language.pick("重新載入", "重新载入")) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (models.isNotEmpty()) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(10.dp), placeholder = { Text(language.pick("搜尋模型", "搜索模型")) }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, language.pick("清除", "清除")) } })
            when { loading -> LoadingOverlay(language.pick("載入模型...", "载入模型...")); models.isEmpty() -> EmptyState(language.pick("尚未取得模型", "尚未取得模型"), language.pick("可重新載入，或在設定頁手動填寫模型 ID。", "可重新载入，或在设置页手动填写模型 ID。")); filtered.isEmpty() -> EmptyState(language.pick("找不到符合的模型", "找不到符合的模型"), language.pick("清除搜尋文字後再試一次。", "清除搜索文字后再试一次。")); else -> LazyColumn { items(filtered, key = { it }) { model -> Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).clickable { viewModel.chooseModel(model) }) { Row(Modifier.fillMaxWidth().padding(14.dp)) { Text(model, Modifier.weight(1f)); if (model == selected) Icon(Icons.Default.Check, language.pick("目前模型", "目前模型")) } } } } }
        }
    }
}

@Composable private fun SelectRow(label: String, selected: Boolean, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(selected, { onClick() }); Text(label) }
@Composable private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { onClick() }); Text(label) }
@Composable private fun ToggleRow(label: String, checked: Boolean, onCheck: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onCheck) }
@Composable private fun DetailedToggleRow(title: String, detail: String, checked: Boolean, onCheck: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, onCheck) }
@Composable private fun Back(language: AppLanguage, onClick: () -> Unit) = IconButton(onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, language.pick("返回", "返回")) }
private fun manualSummaryModeLabel(mode: ManualSummaryMode, language: AppLanguage): String = when (mode) {
    ManualSummaryMode.UN_SUMMARIZED -> language.pick("壓縮未摘要的較早訊息", "压缩未摘要的较早消息")
    ManualSummaryMode.REBUILD_ALL -> language.pick("重新壓縮全部較早訊息", "重新压缩全部较早消息")
}
@Composable private fun LoadingOverlay(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(text) } } }
@Composable private fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail) } }

@Composable
private fun ChatBackground(path: String, darkTheme: Boolean) {
    val bitmap = remember(path) { path.takeIf(String::isNotBlank)?.let(BitmapFactory::decodeFile) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(if (darkTheme) Color.Black.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.54f)))
    }
}

private fun LazyListState.isNearBottom(lastIndex: Int, thresholdPx: Int = 96): Boolean {
    if (lastIndex < 0) return true
    val visibleLast = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (visibleLast.index < lastIndex) return false
    val itemBottom = visibleLast.offset + visibleLast.size
    return itemBottom <= layoutInfo.viewportEndOffset + thresholdPx
}

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
private fun ErrorDialog(error: UiError, language: AppLanguage, onDismiss: () -> Unit, onSettings: () -> Unit, onTrim: () -> Unit, onNew: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(error.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(error.message); Text(error.suggestion, fontWeight = FontWeight.Bold) } }, confirmButton = {
        if (error.kind == ErrorKind.CONTEXT_LENGTH) TextButton(onClick = onTrim) { Text(language.pick("裁切舊訊息並重試", "裁切旧消息并重试")) } else TextButton(onClick = onDismiss) { Text(language.pick("關閉", "关闭")) }
    }, dismissButton = { if (error.kind == ErrorKind.CONTEXT_LENGTH) TextButton(onClick = onNew) { Text(language.pick("建立新對話", "建立新对话")) } else TextButton(onClick = onSettings) { Text(language.pick("前往設定", "前往设置")) } })
}
