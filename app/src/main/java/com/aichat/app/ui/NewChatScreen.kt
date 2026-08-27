package com.aichat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.AppLanguage

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
                        language.pick("開始新對話", "开始新对话"),
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
                Button(
                    onClick = viewModel::createConfiguredConversation,
                    Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(language.pick("開始對話", "开始对话"), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 22.dp, top = 8.dp, end = 22.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AvatarCircle(character?.name ?: "?", character?.id ?: "none", size = 64.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                character?.name ?: language.pick("一般聊天", "一般聊天"),
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (!character?.summary.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    character?.summary.orEmpty(),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (greetings.size > 1) {
                item { SectionTitle(language.pick("開場白", "开场白")) }
                itemsIndexed(greetings, key = { index, _ -> "greeting$index" }) { index, option ->
                    RadioCard(
                        selected = greeting == option,
                        title = if (index == 0) language.pick("預設開場白", "预设开场白") else language.pick("替代開場白 $index", "替代开场白 $index"),
                        subtitle = option.replace("*", ""),
                        subtitleMaxLines = Int.MAX_VALUE,
                        onClick = { viewModel.selectNewChatGreeting(option) },
                    )
                }
            }
            item {
                SectionTitle("PERSONA", badge = language.pick("可不指定", "可不指定"))
            }
            item {
                RadioCard(
                    selected = personaId == null,
                    title = language.pick("不指定 Persona", "不指定 Persona"),
                    subtitle = null,
                    onClick = { viewModel.selectNewChatPersona(null) },
                )
            }
            itemsIndexed(personas, key = { _, p -> p.id }) { _, p ->
                RadioCard(
                    selected = personaId == p.id,
                    title = p.name,
                    subtitle = p.summary.ifBlank { null },
                    onClick = { viewModel.selectNewChatPersona(p.id) },
                )
            }
            item {
                SectionTitle(language.pick("啟用世界設定集", "启用世界设定集"), badge = language.pick("可複選", "可复选"))
            }
            if (sets.isEmpty()) {
                item {
                    Text(
                        language.pick("尚未建立世界設定集。", "尚未建立世界设定集。"),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(sets, key = { _, s -> s.id }) { _, s ->
                CheckCard(
                    checked = s.id in setIds,
                    title = s.name,
                    subtitle = null,
                    onClick = { viewModel.toggleNewChatWorldSet(s.id) },
                )
            }
        }
    }
}
