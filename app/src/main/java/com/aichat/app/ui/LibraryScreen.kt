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
internal fun LibraryScreen(onOpenWorldSets: () -> Unit, profilesViewModel: ProfilesViewModel, onRootSelected: (Screen) -> Unit, language: AppLanguage, showBottomBar: Boolean = true) {
    val personas by profilesViewModel.personas.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { profilesViewModel.importDocument(it, ImportTarget.PERSONA) } }
    Scaffold(topBar = { CompactTopBar(language.pick("資料庫", "资料库")) }, bottomBar = { if (showBottomBar) RootBottomBar(Screen.LIBRARY, language, onRootSelected) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val cardShape = RoundedCornerShape(24.dp)
                Card(
                    Modifier.fillMaxWidth().clippedClickable(cardShape, onOpenWorldSets),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = minimalCardContainerColor()),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
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
                    IconButton(onClick = { profilesViewModel.newProfile(ProfileType.PERSONA) }) { Icon(Icons.Default.Add, language.pick("新增 Persona", "新增 Persona")) }
                }
            }
            if (personas.isEmpty()) item { Text(language.pick("尚未建立 Persona。你仍然可以不指定身份直接聊天。", "尚未建立 Persona。你仍然可以不指定身份直接聊天。")) }
            items(personas, key = { it.id }) { ProfileRow(it, false, profilesViewModel, language) }
        }
    }
}
