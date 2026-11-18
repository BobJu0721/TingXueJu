package com.aichat.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.*
import com.aichat.app.domain.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBranchScreen(vm: BranchTreeViewModel, chat: ChatViewModel, language: AppLanguage,
    stateHolder: SaveableStateHolder? = null, onBack: () -> Unit) {
    val id by chat.selectedConversationId.collectAsStateWithLifecycle()
    key(id) {
        if (stateHolder != null) stateHolder.SaveableStateProvider(id.orEmpty()) { ChatBranchContent(id, vm, chat, language, onBack) }
        else ChatBranchContent(id, vm, chat, language, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBranchContent(id: String?, vm: BranchTreeViewModel, chat: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val saving by chat.isSavingChatInfo.collectAsStateWithLifecycle()
    val switching by chat.isSwitchingBranch.collectAsStateWithLifecycle()
    val busy = modelWorkBusy(chat) || saving || switching
    LaunchedEffect(id) { id?.let(vm::open) }
    var selectedNode by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot?.takeIf { it.conversationId == id }
    val tree = state.tree.takeIf { snapshot != null }
    val listState = rememberBranchListState(id.orEmpty())
    val rowsByBranch = state.rowsByBranch
    val numbers = remember(snapshot) { snapshot?.routes?.mapIndexed { i, branch -> branch.id to i + 1 }?.toMap().orEmpty() }
    fun routeName(branch: String) = language.pick("路線 ${numbers[branch] ?: "?"}", "路线 ${numbers[branch] ?: "?"}")
    Scaffold(contentWindowInsets = WindowInsets(0.dp), topBar = {
        Column {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Back(language, onBack)
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(language.pick("對話分支", "对话分支"), fontWeight = FontWeight.Bold)
                    Text(snapshot?.let { "${it.title} · " + language.pick("目前：", "目前：") + routeName(it.activeBranchId) }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Hairline()
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            when {
                id == null -> Text(language.pick("請先開啟聊天室。", "请先打开聊天室。"), Modifier.padding(24.dp))
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                state.error != null -> { Text(state.error!!, Modifier.padding(24.dp)); TextButton(vm::retry) { Text(language.pick("重試", "重试")) } }
                snapshot != null && snapshot.rows.isEmpty() -> Text(language.pick("開始對話後，這裡會顯示故事路線", "开始对话后，这里会显示故事路线"), Modifier.padding(24.dp))
                snapshot != null && snapshot.routes.size == 1 -> Text(language.pick("編輯訊息、重新生成或續寫就會產生分支", "编辑消息、重新生成或续写就会产生分支"), Modifier.padding(24.dp))
                tree != null && snapshot != null -> {
                    BranchTreeList(tree, snapshot.activeBranchId, listState, language, numbers, onNode = { node ->
                            selectedNode = node.id
                            if (node.branchIds.size == 1) vm.select(node.branchIds.first(), if (node.terminalBranchIds.isEmpty()) node.rows.firstOrNull()?.messageId else null)
                            else choosing = true
                        }, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
    val selected = tree?.nodes?.get(selectedNode)
    if (choosing && selected != null && snapshot != null) ModalBottomSheet(onDismissRequest = { choosing = false }) {
        Text(language.pick("選擇路線", "选择路线"), Modifier.padding(horizontal = 24.dp), fontWeight = FontWeight.Bold)
        if (selected.children.isNotEmpty() && snapshot.activeBranchId !in selected.branchIds && selected.id != "root") {
            TextButton({ listState.toggle(tree!!, selected.id); choosing = false }) { Text(language.pick("展開／收合旁支", "展开／收起旁支")) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
            items(selected.branchIds, key = { it }) { branch ->
                ListItem(headlineContent = { Text(routeName(branch)) }, supportingContent = {
                    Text(if (branch == snapshot.activeBranchId) language.pick("目前使用", "目前使用") else "${rowsByBranch[branch].orEmpty().count { !it.deleted && it.status != MessageVersionStatus.DRAFT }} 則")
                }, trailingContent = { TextButton({ choosing = false; vm.select(branch, if (selected.terminalBranchIds.isEmpty()) selected.rows.firstOrNull()?.messageId else null) }) { Text(language.pick("預覽", "预览")) } })
            }
        }
    }
    val p = preview
    if (p != null && snapshot != null) ModalBottomSheet(onDismissRequest = { if (!switching) vm.closePreview() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        val route = snapshot.routes.firstOrNull { it.id == p.branchId }
        val rows = rowsByBranch[p.branchId].orEmpty()
        val unavailable = route == null || rows.any { (!it.deleted && !it.valid) || it.status == MessageVersionStatus.DRAFT }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(routeName(p.branchId) + if (snapshot.activeBranchId == p.branchId) language.pick("・目前使用", "・目前使用") else "", fontWeight = FontWeight.Bold)
            if (route != null) Text(DateFormat.getDateTimeInstance().format(Date(route.createdAt)), style = MaterialTheme.typography.bodySmall)
            Text("${rows.count { !it.deleted && it.status != MessageVersionStatus.DRAFT }} " + language.pick("則訊息", "则消息"))
            if (selected != null && selected.children.isNotEmpty() && snapshot.activeBranchId !in selected.branchIds && selected.id != "root") {
                TextButton({ listState.toggle(tree!!, selected.id); vm.closePreview() }) { Text(language.pick("展開／收合旁支", "展开／收起旁支")) }
            }
            val source = route?.forkVersionId?.let { version -> rows.firstOrNull { it.versionId == version }?.source }
            Text(when (source) {
                MessageVersionSource.USER_EDIT, MessageVersionSource.AI_EDIT -> language.pick("編輯訊息", "编辑消息")
                MessageVersionSource.REGENERATED -> language.pick("生成另一版", "生成另一版")
                MessageVersionSource.CONTINUATION -> language.pick("續寫", "续写")
                else -> language.pick("其他路線", "其他路线")
            }, style = MaterialTheme.typography.bodySmall)
            if (route?.legacyIncomplete == true) Text(language.pick("部分舊紀錄可能未保存", "部分旧记录可能未保存"))
            val statuses = rows.mapNotNull { it.status }.toSet()
            if (MessageVersionStatus.PARTIAL in statuses || MessageVersionStatus.INTERRUPTED in statuses) Text(language.pick("包含部分完成／已中斷內容", "包含部分完成／已中断内容"))
            if (unavailable) Text(language.pick("路線資料不完整或仍在生成，暫時不能切換。", "路线数据不完整或仍在生成，暂时不能切换。"), color = MaterialTheme.colorScheme.error)
            if (p.error != null) Text(p.error, color = MaterialTheme.colorScheme.error)
            if (busy) Text(language.pick("生成、摘要、儲存或代擬進行中，完成後才能切換。", "生成、摘要、保存或代拟进行中，完成后才能切换。"))
            if (p.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val page = p.page
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (page != null && page.start > 0) item { TextButton({ vm.loadPreview((page.start - 50).coerceAtLeast(0)) }) { Text(language.pick("載入較早 50 則", "加载较早 50 则")) } }
                items(page?.rows.orEmpty(), key = { it.messageId }) { row ->
                    var full by rememberSaveable(p.branchId, row.messageId, row.versionId) { mutableStateOf(false) }
                    var overflow by remember { mutableStateOf(false) }
                    Column {
                        Text(treeSpeaker(row.role, row.kind, row.authorName, language) + if (row.excluded) language.pick("・不提供給 AI", "・不提供给 AI") else "", fontWeight = FontWeight.Bold)
                        Text(if (row.deleted) language.pick("此分岔訊息已刪除", "此分岔消息已删除") else row.content,
                            maxLines = if (full) Int.MAX_VALUE else 6, onTextLayout = { overflow = it.hasVisualOverflow })
                        if (overflow || full) TextButton({ full = !full }) { Text(if (full) language.pick("收合全文", "收起全文") else language.pick("展開全文", "展开全文")) }
                    }
                }
                if (page != null && page.start + page.rows.size < page.total) item { TextButton({ vm.loadPreview(page.start + page.rows.size) }) { Text(language.pick("載入後續 50 則", "加载后续 50 则")) } }
            }
            Button(onClick = {
                val expected = vm.selectionSnapshot ?: return@Button
                chat.switchToBranch(expected, p.branchId, p.messageId) { result ->
                    if (result == BranchSwitchResult.SUCCESS) { vm.closePreview(); onBack() } else vm.switchFailed(result)
                }
            }, enabled = !busy && !p.loading && p.error == null && !unavailable,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 48.dp)) {
                Text(if (snapshot.activeBranchId == p.branchId) language.pick("返回聊天室查看", "返回聊天室查看") else language.pick("切換到這條路線", "切换到这条路线"))
            }
        }
    }
}

internal fun treeSpeaker(role: String, kind: MessageKind, author: String?, language: AppLanguage): String = when (kind) {
    MessageKind.PRIVATE_NOTE -> language.pick("私人註記・不提供給 AI", "私人注记・不提供给 AI")
    MessageKind.NARRATION -> language.pick("旁白", "旁白")
    MessageKind.AUTHORED_CHARACTER -> author.orEmpty()
    else -> if (role == "assistant") "AI" else language.pick("使用者", "使用者")
}
