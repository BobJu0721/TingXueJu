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
    val isCharacter = draft.type == ProfileType.CHARACTER
    val title = if (isCharacter) language.pick("編輯角色", "编辑角色") else language.pick("編輯 Persona", "编辑 Persona")
    val saveLabel = if (isCharacter) language.pick("儲存角色", "保存角色") else language.pick("儲存 Persona", "保存 Persona")
    fun saveIt() {
        viewModel.saveProfile(ProfileDraft(draft.id, draft.type, name, summary, personality, background, examples, greeting, alternates.lines().filter(String::isNotBlank), instructions))
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
                        title,
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .clickable { saveIt() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Edit, language.pick("儲存", "保存"), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
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
                    onClick = { saveIt() },
                    Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(saveLabel, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FieldBlock(
                label = language.pick("名稱", "名称"),
                value = name,
                onValueChange = { name = it },
                placeholder = language.pick("例如：艾莉亞、本人、第三人稱旁白", "例如：艾莉亚、本人、第三人称旁白"),
                required = true,
            )
            FieldBlock(
                label = language.pick("簡介", "简介"),
                value = summary,
                onValueChange = { summary = it },
                placeholder = language.pick("一句話描述這個角色", "一句话描述这个角色"),
                exampleText = language.pick("一名尋找失落城市的旅行學者。", "一名寻找失落城市的旅行学者。"),
            )
            FieldBlock(
                label = language.pick("個性", "个性"),
                value = personality,
                onValueChange = { personality = it },
                placeholder = language.pick("她／他是什麼樣的人？", "她／他是什么样的人？"),
                minLines = 3,
                exampleText = language.pick("冷靜、觀察敏銳，面對熟人會偶爾開玩笑。", "冷静、观察敏锐，面对熟人会偶尔开玩笑。"),
            )
            FieldBlock(
                label = language.pick("背景", "背景"),
                value = background,
                onValueChange = { background = it },
                placeholder = language.pick("出身、經歷與動機", "出身、经历与动机"),
                minLines = 4,
                exampleText = language.pick("銀湖村的遊俠，十年前村子遭蝕影襲擊後獨自踏上旅途。", "银湖村的游侠，十年前村子遭蚀影袭击后独自踏上旅途。"),
            )
            FieldBlock(
                label = language.pick("範例對話", "范例对话"),
                value = examples,
                onValueChange = { examples = it },
                placeholder = language.pick("示範語氣與格式，使用 {{user}} 與 {{char}}", "示范语气与格式，使用 {{user}} 与 {{char}}"),
                minLines = 3,
                exampleText = language.pick(
                    "{{user}}: 妳害怕月蝕嗎？\n{{char}}: *她的手停在弓弦上。*「自從那夜之後，我只信我看過的影子。」",
                    "{{user}}: 你害怕月蚀吗？\n{{char}}: *她的手停在弓弦上。*「自从那夜之后，我只信我看过的影子。」",
                ),
            )
            FieldBlock(
                label = language.pick("開場白", "开场白"),
                value = greeting,
                onValueChange = { greeting = it },
                placeholder = language.pick("第一則 AI 訊息", "第一则 AI 消息"),
                minLines = 3,
                exampleText = language.pick("*銀湖的霧氣緩緩散開……*「旅人，你終於來了。」", "*银湖的雾气缓缓散开……*「旅人，你终于来了。」"),
            )
            FieldBlock(
                label = language.pick("替代開場白", "替代开场白"),
                value = alternates,
                onValueChange = { alternates = it },
                placeholder = language.pick("每行一個替代版本", "每行一个替代版本"),
                minLines = 3,
            )
            FieldBlock(
                label = language.pick("額外指示", "额外指示"),
                value = instructions,
                onValueChange = { instructions = it },
                placeholder = language.pick("給 AI 的系統級提示（選填）", "给 AI 的系统级提示（选填）"),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FieldBlock(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1,
    required: Boolean = false,
    exampleText: String? = null,
) {
    var showExample by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (required) {
                    Spacer(Modifier.width(2.dp))
                    Text("*", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            if (exampleText != null) {
                Text(
                    language.pick("示範", "示范"),
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showExample = !showExample }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            minLines = minLines,
            maxLines = if (minLines == 1) 1 else 8,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
        if (showExample && exampleText != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFEFEFEF) else Color(0xFF3A3A3C),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(exampleText, Modifier.padding(10.dp), fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
