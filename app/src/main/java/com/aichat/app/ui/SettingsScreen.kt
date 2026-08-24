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
    val lang = settings.language
    Scaffold(topBar = { CompactTopBar(lang.pick("設定", "设置")) }, bottomBar = { if (showBottomBar) RootBottomBar(Screen.SETTINGS, lang, onRootSelected) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val apiCardShape = RoundedCornerShape(11.dp)
            Card(
                Modifier.fillMaxWidth().clippedClickable(apiCardShape, viewModel::openApiSettings),
                shape = apiCardShape,
                colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(lang.pick("API 設定", "API 设置"), fontWeight = FontWeight.Bold)
                        Text(if (settings.provider == Provider.CUSTOM) lang.pick("自訂端點", "自定义端点") else settings.provider.label, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, lang.pick("進入", "进入"))
                }
            }
            Text(lang.pick("介面語言", "界面语言"), fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { languageMenu = true }, shape = RoundedCornerShape(14.dp)) { Text(language.label) }
                DropdownMenu(languageMenu, { languageMenu = false }) {
                    AppLanguage.entries.forEach { option ->
                        DropdownMenuItem({ Text(option.label) }, { language = option; languageMenu = false })
                    }
                }
            }
            Button(onClick = { viewModel.saveAppearanceSettings(settings.darkTheme, language) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(lang.pick("儲存設定", "保存设置")) }
            Text(lang.pick("API Key 使用 Android Keystore 保護。App 不會自動備份本機內容。", "API Key 使用 Android Keystore 保护。App 不会自动备份本机内容。"), style = MaterialTheme.typography.bodySmall)
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
        topBar = {
            CompactTopBar(
                language.pick("API 設定", "API 设置"),
                navigationIcon = { Back(language, onBack) },
            )
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
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(language.pick("目前使用", "当前使用"), fontWeight = FontWeight.Bold)
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(activeName, fontWeight = FontWeight.Bold)
                Text(if (settings.provider == Provider.CUSTOM || settings.provider == Provider.CLOUDFLARE) settings.resolvedBaseUrl else settings.provider.baseUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(language.pick("模型在聊天頁右上角選擇。", "模型在聊天页右上角选择。"), style = MaterialTheme.typography.bodySmall)
        Text(language.pick("端點列表", "端点列表"), fontWeight = FontWeight.Bold)
        Provider.entries.filter { it != Provider.CUSTOM }.forEach { provider ->
            ApiEndpointCard(
                title = provider.label,
                detail = provider.baseUrl,
                badge = if (settings.provider == provider) language.pick("目前使用", "当前使用") else language.pick("內建", "内置"),
                keySaved = SecretStore.providerStorageKey(provider) in savedKeyIds,
                language = language,
            ) { onEditBuiltIn(provider) }
        }
        presets.forEach { preset ->
            ApiEndpointCard(
                title = preset.name,
                detail = preset.baseUrl,
                badge = if (settings.provider == Provider.CUSTOM && preset.baseUrl.trimEnd('/') == settings.customBaseUrl.trimEnd('/')) language.pick("目前使用", "当前使用") else language.pick("自訂", "自定义"),
                keySaved = SecretStore.customEndpointStorageKey(preset.id) in savedKeyIds,
                language = language,
            ) { onEditCustom(preset.id) }
        }
        OutlinedButton(
            onClick = { onEditCustom(UUID.randomUUID().toString()) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text(language.pick("新增自訂端點", "新增自定义端点"))
        }
    }
}

@Composable
private fun ApiEndpointCard(
    title: String,
    detail: String,
    badge: String,
    keySaved: Boolean,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(11.dp)
    Card(
        Modifier.fillMaxWidth().clippedClickable(cardShape, onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (keySaved) language.pick("Key 已保存", "Key 已保存") else language.pick("尚未保存 Key", "尚未保存 Key"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    Scaffold(
        topBar = { CompactTopBar(language.pick("API 端點", "API 端点"), navigationIcon = { Back(language, onBack) }) },
    ) { padding ->
        BuiltInEndpointDetail(
            provider,
            uiState.settings.cloudflareAccountId,
            SecretStore.providerStorageKey(provider) in uiState.savedKeyIds,
            viewModel,
            language,
            Modifier.fillMaxSize().padding(padding),
        )
    }
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
    Scaffold(
        topBar = { CompactTopBar(language.pick("API 端點", "API 端点"), navigationIcon = { Back(language, onBack) }) },
    ) { padding ->
        CustomEndpointDetail(
            id = id,
            initialName = preset?.name.orEmpty(),
            initialBaseUrl = preset?.baseUrl.orEmpty(),
            isExisting = preset != null,
            hasSavedKey = SecretStore.customEndpointStorageKey(id) in uiState.savedKeyIds,
            viewModel = viewModel,
            language = language,
            modifier = Modifier.fillMaxSize().padding(padding),
            onClose = onBack,
        )
    }
}

@Composable
private fun BuiltInEndpointDetail(
    provider: Provider,
    initialCloudflareAccountId: String,
    hasSavedKey: Boolean,
    viewModel: SettingsViewModel,
    language: AppLanguage,
    modifier: Modifier,
) {
    var key by remember(provider) { mutableStateOf("") }
    var accountId by remember(provider, initialCloudflareAccountId) { mutableStateOf(initialCloudflareAccountId) }
    val cloudflareReady = provider != Provider.CLOUDFLARE || accountId.isNotBlank()
    val endpointUrl = if (provider == Provider.CLOUDFLARE) {
        provider.baseUrl.replace("{ACCOUNT_ID}", accountId.trim().ifBlank { "{ACCOUNT_ID}" })
    } else {
        provider.baseUrl
    }
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(provider.label, fontWeight = FontWeight.Bold)
        Text(endpointUrl, style = MaterialTheme.typography.bodySmall)
        if (provider == Provider.CLOUDFLARE) {
            OutlinedTextField(accountId, { accountId = it }, Modifier.fillMaxWidth(), label = { Text("Account ID") }, singleLine = true)
        }
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, placeholder = { Text(if (hasSavedKey) language.pick("已保存；留白可沿用", "已保存；留白可沿用") else language.pick("填入 API Key", "填入 API Key")) })
        Button(
            onClick = { viewModel.saveBuiltInEndpoint(provider, key, accountId, makeActive = false); key = "" },
            modifier = Modifier.fillMaxWidth(),
            enabled = cloudflareReady && (key.isNotBlank() || (provider == Provider.CLOUDFLARE && hasSavedKey)),
            shape = RoundedCornerShape(14.dp),
        ) { Text(language.pick("儲存", "保存")) }
        Button(
            onClick = { viewModel.saveBuiltInEndpoint(provider, key, accountId, makeActive = true); key = "" },
            modifier = Modifier.fillMaxWidth(),
            enabled = cloudflareReady && (key.isNotBlank() || hasSavedKey),
            shape = RoundedCornerShape(14.dp),
        ) { Text(language.pick("設為目前使用", "设为当前使用")) }
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
    modifier: Modifier,
    onClose: () -> Unit,
) {
    var name by remember(id, initialName) { mutableStateOf(initialName) }
    var baseUrl by remember(id, initialBaseUrl) { mutableStateOf(initialBaseUrl) }
    var key by remember(id) { mutableStateOf("") }
    val canSave = name.isNotBlank() && baseUrl.isNotBlank() && (key.isNotBlank() || (isExisting && hasSavedKey))
    Column(modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(language.pick("名稱", "名称")) }, singleLine = true)
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true, supportingText = { Text(language.pick("HTTP 可以使用，但傳送前會警告可能外洩。", "HTTP 可以使用，但发送前会警告可能外泄。")) })
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, placeholder = { Text(if (hasSavedKey) language.pick("已保存；留白可沿用", "已保存；留白可沿用") else language.pick("填入 API Key", "填入 API Key")) })
        Button(
            onClick = { viewModel.saveCustomEndpoint(id, name, baseUrl, key, makeActive = false); key = "" },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave,
            shape = RoundedCornerShape(14.dp),
        ) { Text(language.pick("儲存", "保存")) }
        Button(
            onClick = { viewModel.saveCustomEndpoint(id, name, baseUrl, key, makeActive = true); key = "" },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave,
            shape = RoundedCornerShape(14.dp),
        ) { Text(language.pick("設為目前使用", "设为当前使用")) }
        if (isExisting) {
            OutlinedButton(
                onClick = { viewModel.deleteCustomEndpoint(id); onClose() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) { Text(language.pick("刪除", "删除")) }
        }
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
    Scaffold(topBar = { CompactTopBar(language.pick("選擇模型", "选择模型"), navigationIcon = { Back(language, onBack) }, actions = { IconButton(onClick = viewModel::refreshModels) { Icon(Icons.Default.Refresh, language.pick("重新載入", "重新载入")) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (reasoningMode != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(language.pick("思考模式", "思考模式"), fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ReasoningMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = reasoningMode == mode,
                                onClick = { onReasoningModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ReasoningMode.entries.size),
                                label = {
                                    Text(
                                        when (mode) {
                                            ReasoningMode.AUTO -> language.pick("自動", "自动")
                                            ReasoningMode.ON -> language.pick("開啟", "开启")
                                            ReasoningMode.OFF -> language.pick("關閉", "关闭")
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(10.dp), placeholder = { Text(language.pick("搜尋或輸入模型 ID", "搜索或输入模型 ID")) }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, language.pick("清除", "清除")) } })
            when {
                visibleModels.isNotEmpty() -> LazyColumn {
                    items(visibleModels, key = { it.first }) { (model, isManual) ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clippedClickable(RoundedCornerShape(11.dp)) { viewModel.chooseModel(model) }, shape = RoundedCornerShape(11.dp), colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isManual) language.pick("使用「$model」", "使用「$model」") else model, Modifier.weight(1f))
                                if (model == selected) Icon(Icons.Default.Check, language.pick("目前模型", "目前模型"), tint = MaterialTheme.colorScheme.primary)
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
