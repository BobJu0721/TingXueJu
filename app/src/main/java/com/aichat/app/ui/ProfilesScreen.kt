package com.aichat.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextAlign
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
internal fun ProfilesScreen(viewModel: ProfilesViewModel, onRootSelected: (Screen) -> Unit, type: ProfileType, language: AppLanguage, showBottomBar: Boolean = true) {
    val profiles by (if (type == ProfileType.CHARACTER) viewModel.characters else viewModel.personas).collectAsStateWithLifecycle()
    val importTarget = if (type == ProfileType.CHARACTER) ImportTarget.CHARACTER else ImportTarget.PERSONA
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importDocument(it, importTarget) } }
    val title = if (type == ProfileType.CHARACTER) language.pick("角色", "角色") else "Persona"
    val countLabel = if (type == ProfileType.CHARACTER) language.pick("${profiles.size} 位角色", "${profiles.size} 位角色") else "${profiles.size} 個"
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            LargeTitleHeader(
                title = title,
                countText = if (profiles.isEmpty()) null else countLabel,
                importAction = { launcher.launch(DOCUMENT_TYPES) },
                importDescription = language.pick("匯入文件", "导入文件"),
                onAdd = { viewModel.newProfile(type) },
                addDescription = language.pick("新增$title", "新增$title"),
            )
        },
        bottomBar = { if (showBottomBar && type == ProfileType.CHARACTER) RootBottomBar(Screen.CHARACTERS, language, onRootSelected) },
    ) { padding ->
        if (profiles.isEmpty()) {
            EmptyState(
                language.pick("還沒有$title", "还没有$title"),
                language.pick("按右上角＋手動建立，或從 TXT、JSON、DOCX 文件交給 AI 整理。", "按右上角＋手动建立，或从 TXT、JSON、DOCX 文件交给 AI 整理。"),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 22.dp, top = 8.dp, end = 22.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(profiles, key = { it.id }) { profile -> ProfileRow(profile, type == ProfileType.CHARACTER, viewModel, language) }
                if (type == ProfileType.CHARACTER) {
                    item {
                        Text(
                            language.pick("支援匯入 TXT / JSON / DOCX 角色卡", "支援导入 TXT / JSON / DOCX 角色卡"),
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfileRow(profile: ProfileEntity, canChat: Boolean, viewModel: ProfilesViewModel, language: AppLanguage) {
    var pendingDelete by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(20.dp)
    Card(
        Modifier.fillMaxWidth().clippedCombinedClickable(
            cardShape,
            onClick = { viewModel.editProfile(profile) },
            onLongClick = { pendingDelete = true },
        ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(profile.name, profile.id)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.summary.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        profile.summary,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!canChat) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
            }
            if (canChat) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { viewModel.startChat(profile.id) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Chat, language.pick("開始聊天", "开始聊天"), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp))
                        Text(language.pick("聊天", "聊天"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (pendingDelete) {
        DeleteConfirmDialog(
            title = if (profile.type == ProfileType.CHARACTER) language.pick("刪除角色", "删除角色") else language.pick("刪除 Persona", "删除 Persona"),
            message = profile.name,
            language = language,
            onConfirm = {
                viewModel.deleteProfile(profile)
                pendingDelete = false
            },
            onDismiss = { pendingDelete = false },
        )
    }
}


@Composable
internal fun ProfileEditScreen(viewModel: ProfilesViewModel, language: AppLanguage, onBack: () -> Unit) {
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
    Scaffold(topBar = { CompactTopBar(title, navigationIcon = { Back(language, onBack) }) }) { padding ->
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
            if (showHelp) Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) { Text(language.pick("簡介：一名尋找失落城市的旅行學者。\n個性：冷靜、觀察敏銳，面對熟人會偶爾開玩笑。\n背景：曾在北方學院研究古代文字。\n範例對話：我不會急著下結論，先看看牆上的刻痕。\n開場白：你也注意到這扇門了嗎？", "简介：一名寻找失落城市的旅行学者。\n个性：冷静、观察敏锐，面对熟人会偶尔开玩笑。\n背景：曾在北方学院研究古代文字。\n范例对话：我不会急着下结论，先看看墙上的刻痕。\n开场白：你也注意到这扇门了吗？"), Modifier.padding(12.dp)) }
            Button(onClick = {
                viewModel.saveProfile(ProfileDraft(draft.id, draft.type, name, summary, personality, background, examples, greeting, alternates.lines().filter(String::isNotBlank), instructions))
            }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(language.pick("儲存", "保存")) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
