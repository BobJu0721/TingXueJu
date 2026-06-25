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
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsScreen(viewModel: SettingsViewModel, onRootSelected: (Screen) -> Unit, settings: AppSettings, showBottomBar: Boolean = true) {
    var provider by remember { mutableStateOf(settings.provider) }; var base by remember { mutableStateOf(settings.customBaseUrl) }
    var model by remember { mutableStateOf(settings.model) }; var key by remember { mutableStateOf("") }; var dark by remember { mutableStateOf(settings.darkTheme) }
    var language by remember(settings.language) { mutableStateOf(settings.language) }
    var menu by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    val lang = settings.language
    LaunchedEffect(provider) { if (provider != settings.provider) model = provider.defaultModel; key = "" }
    Scaffold(topBar = { CompactTopBar(lang.pick("設定", "设置")) }, bottomBar = { if (showBottomBar) RootBottomBar(Screen.SETTINGS, lang, onRootSelected) }) { padding ->
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
internal fun ModelsScreen(viewModel: SettingsViewModel, selected: String, language: AppLanguage, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val models = uiState.models
    val loading = uiState.isLoadingModels
    var query by remember { mutableStateOf("") }; val filtered = remember(models, query) { filterModels(models, query) }
    Scaffold(topBar = { CompactTopBar(language.pick("選擇模型", "选择模型"), navigationIcon = { Back(language, onBack) }, actions = { IconButton(onClick = viewModel::refreshModels) { Icon(Icons.Default.Refresh, language.pick("重新載入", "重新载入")) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (models.isNotEmpty()) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(10.dp), placeholder = { Text(language.pick("搜尋模型", "搜索模型")) }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, language.pick("清除", "清除")) } })
            when { loading -> LoadingOverlay(language.pick("載入模型...", "载入模型...")); models.isEmpty() -> EmptyState(language.pick("尚未取得模型", "尚未取得模型"), language.pick("可重新載入，或在設定頁手動填寫模型 ID。", "可重新载入，或在设置页手动填写模型 ID。")); filtered.isEmpty() -> EmptyState(language.pick("找不到符合的模型", "找不到符合的模型"), language.pick("清除搜尋文字後再試一次。", "清除搜索文字后再试一次。")); else -> LazyColumn { items(filtered, key = { it }) { model -> Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).clickable { viewModel.chooseModel(model) }) { Row(Modifier.fillMaxWidth().padding(14.dp)) { Text(model, Modifier.weight(1f)); if (model == selected) Icon(Icons.Default.Check, language.pick("目前模型", "目前模型")) } } } } }
        }
    }
}
