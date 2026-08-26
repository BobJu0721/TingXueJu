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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.input.VisualTransformation
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
import java.util.UUID
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsScreen(viewModel: SettingsViewModel, onRootSelected: (Screen) -> Unit, settings: AppSettings, showBottomBar: Boolean = true) {
    var language by remember(settings.language) { mutableStateOf(settings.language) }
    var languageMenu by remember { mutableStateOf(false) }
    var dark by remember(settings.darkTheme) { mutableStateOf(settings.darkTheme) }
    val lang = settings.language
    val cardShape = RoundedCornerShape(20.dp)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { LargeTitleHeader(lang.pick("設定", "设置")) },
        bottomBar = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveAppearanceSettings(dark, language) },
                        Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                    ) { Text(lang.pick("套用設定", "应用设置"), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }
                if (showBottomBar) RootBottomBar(Screen.SETTINGS, lang, onRootSelected)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                lang.pick("連線", "连线"),
                Modifier.padding(top = 4.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Card(
                Modifier.fillMaxWidth().clippedClickable(cardShape, viewModel::openApiSettings),
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Default.Link)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lang.pick("API 與端點", "API 与端点"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (settings.provider == Provider.CUSTOM) lang.pick("自訂端點", "自定义端点") else lang.pick("目前使用：", "当前使用：") + settings.provider.label,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                }
            }
            Text(
                lang.pick("一般", "一般"),
                Modifier.padding(top = 4.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Box {
                Card(
                    Modifier.fillMaxWidth().clippedClickable(cardShape) { languageMenu = true },
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Default.Public)
                        Spacer(Modifier.width(16.dp))
                        Text(lang.pick("語言", "语言"), Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(language.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                DropdownMenu(languageMenu, { languageMenu = false }) {
                    AppLanguage.entries.forEach { option ->
                        DropdownMenuItem({ Text(option.label) }, { language = option; languageMenu = false })
                    }
                }
            }
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Default.DarkMode)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lang.pick("深色模式", "深色模式"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(lang.pick("App 自有主題切換", "App 自有主题切换"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(dark, { dark = it }, colors = minimalSwitchColors())
                }
            }
            Text(
                lang.pick("API Key 使用 Android Keystore 保護。App 不會自動備份本機內容。", "API Key 使用 Android Keystore 保护。App 不会自动备份本机内容。"),
                Modifier.padding(top = 2.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
internal fun ApiSettingsScreen(
    viewModel: SettingsViewModel,
    language: AppLanguage,
    onBack: () -> Unit,
    onEditBuiltIn: (Provider) -> Unit,
    onEditCustom: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val presets = uiState.customEndpointPresets
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
                        language.pick("API 與端點", "API 与端点"),
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
    ) { padding ->
        ApiEndpointList(
            settings,
            presets,
            uiState.savedKeyIds,
            language,
            Modifier.fillMaxSize().padding(padding),
            onEditBuiltIn,
            onEditCustom,
        )
    }
}

private enum class EndpointBadge { CURRENT, KEY_SAVED, NOT_SET, HTTP }

@Composable
private fun ApiEndpointList(
    settings: AppSettings,
    presets: List<CustomEndpointPreset>,
    savedKeyIds: Set<String>,
    language: AppLanguage,
    modifier: Modifier,
    onEditBuiltIn: (Provider) -> Unit,
    onEditCustom: (String) -> Unit,
) {
    val activeCustom = presets.firstOrNull { settings.provider == Provider.CUSTOM && it.baseUrl.trimEnd('/') == settings.customBaseUrl.trimEnd('/') }
    val activeName = if (settings.provider == Provider.CUSTOM) activeCustom?.name ?: language.pick("自訂端點", "自定义端点") else settings.provider.label
    val currentKeySaved = when (settings.provider) {
        Provider.CUSTOM -> activeCustom?.let { SecretStore.customEndpointStorageKey(it.id) in savedKeyIds } ?: false
        else -> SecretStore.providerStorageKey(settings.provider) in savedKeyIds
    }
    LazyColumn(
        modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Default.Bolt)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(language.pick("目前端點", "当前端点"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$activeName · " + if (currentKeySaved) language.pick("API Key 已保存（不明文顯示）", "API Key 已保存（不明文显示）") else language.pick("尚未設定 Key", "尚未设定 Key"),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF34C759)))
                }
            }
        }
        item {
            Text(
                language.pick("模型在聊天頁右上角選擇。", "模型在聊天页右上角选择。"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
        item { SectionTitle(language.pick("內建端點", "内置端点")) }
        items(Provider.entries.filter { it != Provider.CUSTOM }, key = { it.name }) { provider ->
            val keySaved = SecretStore.providerStorageKey(provider) in savedKeyIds
            val badge = when {
                settings.provider == provider -> EndpointBadge.CURRENT
                keySaved -> EndpointBadge.KEY_SAVED
                else -> EndpointBadge.NOT_SET
            }
            ApiEndpointCard(
                letter = provider.label.split(" ").take(2).map { it.first() }.joinToString("").uppercase(),
                title = provider.label,
                detail = provider.baseUrl,
                badge = badge,
                language = language,
            ) { onEditBuiltIn(provider) }
        }
        item { SectionTitle(language.pick("自訂端點", "自定义端点")) }
        if (presets.isEmpty()) {
            item {
                Text(
                    language.pick("尚無自訂端點。", "尚无自定义端点。"),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(presets, key = { it.id }) { preset ->
            val badge = if (settings.provider == Provider.CUSTOM && preset.baseUrl.trimEnd('/') == settings.customBaseUrl.trimEnd('/')) EndpointBadge.CURRENT else EndpointBadge.HTTP
            ApiEndpointCard(
                letter = "⚙",
                title = preset.name,
                detail = preset.baseUrl,
                badge = badge,
                language = language,
            ) { onEditCustom(preset.id) }
        }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawRoundRect(
                            color = Color(0xFF007AFF).copy(alpha = 0.55f),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 9.dp.toPx()))),
                        )
                    }
                    .clickable { onEditCustom(UUID.randomUUID().toString()) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 14.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(language.pick("新增自訂端點", "新增自定义端点"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ApiEndpointCard(
    letter: String,
    title: String,
    detail: String,
    badge: EndpointBadge,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clippedClickable(RoundedCornerShape(16.dp), onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            when (badge) {
                EndpointBadge.CURRENT -> BadgeChip("✓ " + language.pick("目前使用", "当前使用"), Color(0xFF34C759).copy(alpha = 0.15f), Color(0xFF1FA84A))
                EndpointBadge.KEY_SAVED -> BadgeChip(language.pick("Key 已保存", "Key 已保存"), MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), MaterialTheme.colorScheme.onSurfaceVariant)
                EndpointBadge.NOT_SET -> BadgeChip(language.pick("未設定", "未设定"), MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), MaterialTheme.colorScheme.onSurfaceVariant)
                EndpointBadge.HTTP -> BadgeChip("HTTP", Color(0xFFFF9500).copy(alpha = 0.15f), Color(0xFFC77700))
            }
        }
    }
}

@Composable
private fun BadgeChip(text: String, bg: Color, fg: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

@Composable
internal fun BuiltInEndpointScreen(
    viewModel: SettingsViewModel,
    provider: Provider,
    language: AppLanguage,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BuiltInEndpointDetail(
        provider,
        uiState.settings.cloudflareAccountId,
        SecretStore.providerStorageKey(provider) in uiState.savedKeyIds,
        viewModel,
        language,
        onBack,
    )
}

@Composable
internal fun CustomEndpointScreen(
    viewModel: SettingsViewModel,
    id: String,
    language: AppLanguage,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preset = uiState.customEndpointPresets.firstOrNull { it.id == id }
    CustomEndpointDetail(
        id = id,
        initialName = preset?.name.orEmpty(),
        initialBaseUrl = preset?.baseUrl.orEmpty(),
        isExisting = preset != null,
        hasSavedKey = SecretStore.customEndpointStorageKey(id) in uiState.savedKeyIds,
        viewModel = viewModel,
        language = language,
        onClose = onBack,
    )
}

@Composable
private fun BuiltInEndpointDetail(
    provider: Provider,
    initialCloudflareAccountId: String,
    hasSavedKey: Boolean,
    viewModel: SettingsViewModel,
    language: AppLanguage,
    onBack: () -> Unit,
) {
    var key by remember(provider) { mutableStateOf("") }
    var accountId by remember(provider, initialCloudflareAccountId) { mutableStateOf(initialCloudflareAccountId) }
    var showKey by remember { mutableStateOf(false) }
    val cloudflareReady = provider != Provider.CLOUDFLARE || accountId.isNotBlank()
    val canSave = cloudflareReady && (key.isNotBlank() || (provider == Provider.CLOUDFLARE && hasSavedKey))
    val endpointUrl = if (provider == Provider.CLOUDFLARE) {
        provider.baseUrl.replace("{ACCOUNT_ID}", accountId.trim().ifBlank { "{ACCOUNT_ID}" })
    } else {
        provider.baseUrl
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
                        provider.label,
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
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.saveBuiltInEndpoint(provider, key, accountId, makeActive = false); key = "" },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(language.pick("儲存", "保存"), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    Button(
                        onClick = { viewModel.saveBuiltInEndpoint(provider, key, accountId, makeActive = true); key = "" },
                        enabled = cloudflareReady && (key.isNotBlank() || hasSavedKey),
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(language.pick("設為目前使用", "设为当前使用"), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
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
            Text(
                endpointUrl,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (provider == Provider.CLOUDFLARE) {
                EndpointField(
                    label = "Account ID",
                    value = accountId,
                    onValueChange = { accountId = it },
                    placeholder = "Cloudflare Account ID",
                    required = true,
                )
            }
            EndpointField(
                label = "API Key",
                value = key,
                onValueChange = { key = it },
                placeholder = if (hasSavedKey) language.pick("已保存；留白可沿用", "已保存；留白可沿用") else language.pick("填入 API Key", "填入 API Key"),
                trailingLink = if (showKey) language.pick("隱藏", "隐藏") else language.pick("顯示", "显示"),
                onTrailingLink = { showKey = !showKey },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                hint = language.pick("金鑰只保存在你的裝置上，介面永不顯示明文。", "金钥只保存在你的装置上，界面永不显示明文。"),
            )
        }
    }
}

@Composable
private fun CustomEndpointDetail(
    id: String,
    initialName: String,
    initialBaseUrl: String,
    isExisting: Boolean,
    hasSavedKey: Boolean,
    viewModel: SettingsViewModel,
    language: AppLanguage,
    onClose: () -> Unit,
) {
    var name by remember(id, initialName) { mutableStateOf(initialName) }
    var baseUrl by remember(id, initialBaseUrl) { mutableStateOf(initialBaseUrl) }
    var key by remember(id) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val canSave = name.isNotBlank() && baseUrl.isNotBlank() && (key.isNotBlank() || (isExisting && hasSavedKey))
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
                    ) { Back(language, onClose) }
                    Text(
                        if (isExisting) language.pick("編輯自訂端點", "编辑自定义端点") else language.pick("新增自訂端點", "新增自定义端点"),
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.saveCustomEndpoint(id, name, baseUrl, key, makeActive = false); key = "" },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(language.pick("儲存", "保存"), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    Button(
                        onClick = { viewModel.saveCustomEndpoint(id, name, baseUrl, key, makeActive = true); key = "" },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(language.pick("設為目前使用", "设为当前使用"), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
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
            EndpointField(
                label = language.pick("端點名稱", "端点名称"),
                value = name,
                onValueChange = { name = it },
                placeholder = language.pick("例如：我的 Proxy", "例如：我的 Proxy"),
                required = true,
            )
            EndpointField(
                label = "Base URL",
                value = baseUrl,
                onValueChange = { baseUrl = it },
                placeholder = "https://…",
                required = true,
                hint = language.pick("自訂 HTTP 端點以明文發送請求，請確認您信任該位址。", "自定义 HTTP 端点以明文发送请求，请确认您信任该位址。"),
            )
            EndpointField(
                label = "API Key",
                value = key,
                onValueChange = { key = it },
                placeholder = if (hasSavedKey) language.pick("已保存；留白可沿用", "已保存；留白可沿用") else language.pick("貼上金鑰（僅保存在本機）", "贴上金钥（仅保存在本机）"),
                trailingLink = if (showKey) language.pick("隱藏", "隐藏") else language.pick("顯示", "显示"),
                onTrailingLink = { showKey = !showKey },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                hint = language.pick("金鑰只保存在你的裝置上，介面永不顯示明文。", "金钥只保存在你的装置上，界面永不显示明文。"),
            )
            if (isExisting) {
                OutlinedButton(
                    onClick = { viewModel.deleteCustomEndpoint(id); onClose() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(language.pick("刪除此端點", "删除此端点"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun EndpointField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    required: Boolean = false,
    hint: String? = null,
    trailingLink: String? = null,
    onTrailingLink: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
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
            if (trailingLink != null && onTrailingLink != null) {
                Text(
                    trailingLink,
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTrailingLink)
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
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = visualTransformation,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
        hint?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}


@Composable
internal fun ModelsScreen(
    viewModel: SettingsViewModel,
    selected: String,
    reasoningMode: ReasoningMode?,
    language: AppLanguage,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val models = uiState.models
    val loading = uiState.isLoadingModels
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) { filterModels(models, query) }
    val manualModel = remember(models, query) { manualModelCandidate(models, query) }
    val visibleModels = remember(filtered, manualModel) {
        buildList {
            manualModel?.let { add(it to true) }
            filtered.forEach { add(it to false) }
        }
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
                        language.pick("選擇模型", "选择模型"),
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
                            .clickable(onClick = viewModel::refreshModels),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Refresh, language.pick("重新載入", "重新载入"), modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Hairline()
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (reasoningMode != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionTitle(language.pick("思考模式", "思考模式"), badge = language.pick("僅在目前對話顯示", "仅在当前对话显示"))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(3.dp)
                    ) {
                        ReasoningMode.entries.forEach { mode ->
                            val segSelected = reasoningMode == mode
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(if (segSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                                    .clickable { onReasoningModeChange(mode) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    when (mode) {
                                        ReasoningMode.AUTO -> language.pick("自動", "自动")
                                        ReasoningMode.ON -> language.pick("開啟", "开启")
                                        ReasoningMode.OFF -> language.pick("關閉", "关闭")
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = if (segSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (segSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                placeholder = { Text(language.pick("搜尋或輸入模型 ID", "搜索或输入模型 ID")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, language.pick("清除", "清除")) } },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            when {
                visibleModels.isNotEmpty() -> LazyColumn(
                    contentPadding = PaddingValues(start = 22.dp, top = 8.dp, end = 22.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleModels, key = { it.first }) { (model, isManual) ->
                        Card(
                            Modifier.fillMaxWidth().clippedClickable(RoundedCornerShape(16.dp)) { viewModel.chooseModel(model) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (isManual) "✦" else model.take(1).uppercase(),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (isManual) language.pick("使用「$model」", "使用「$model」") else model,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (isManual) language.pick("自訂模型 ID", "自定义模型 ID") else "Model",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (model == selected) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(99.dp))
                                            .background(Color(0xFF34C759).copy(alpha = 0.15f))
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text("✓ " + language.pick("使用中", "使用中"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1FA84A))
                                    }
                                }
                            }
                        }
                    }
                }
                loading -> LoadingOverlay(language.pick("載入模型...", "载入模型..."))
                models.isEmpty() -> EmptyState(language.pick("尚未取得模型", "尚未取得模型"), language.pick("輸入完整模型 ID，或重新載入清單。", "输入完整模型 ID，或重新载入列表。"))
                else -> EmptyState(language.pick("找不到符合的模型", "找不到符合的模型"), language.pick("可直接使用輸入的完整模型 ID。", "可直接使用输入的完整模型 ID。"))
            }
        }
    }
}
