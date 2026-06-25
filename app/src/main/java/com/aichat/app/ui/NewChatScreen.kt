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
@Composable
internal fun NewChatScreen(viewModel: NewChatViewModel, onBack: () -> Unit, language: AppLanguage) {
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
    Scaffold(topBar = { CompactTopBar(language.pick("開始新對話", "开始新对话"), navigationIcon = { Back(language, onBack) }) }) { padding ->
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
