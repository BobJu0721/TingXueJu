package com.aichat.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.*
import com.aichat.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BranchTreeState(
    val snapshot: BranchTreeSnapshot? = null, val tree: BranchTree? = null,
    val error: String? = null, val loading: Boolean = true,
    val rowsByBranch: Map<String, List<BranchTreeRow>> = emptyMap(),
)
data class BranchPreviewState(
    val branchId: String, val messageId: String?, val page: BranchPreviewPage? = null,
    val loading: Boolean = true, val error: String? = null,
)

class BranchTreeViewModel(container: AppContainer, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val repo = container.conversationRepository
    private val _state = MutableStateFlow(BranchTreeState()); val state = _state.asStateFlow()
    private val _expanded = MutableStateFlow(saved.get<ArrayList<String>>("expanded")?.toSet().orEmpty())
    val expanded = _expanded.asStateFlow()
    private val _preview = MutableStateFlow<BranchPreviewState?>(null); val preview = _preview.asStateFlow()
    var selectionSnapshot: BranchTreeSnapshot? = null
        private set
    private var id: String? = null
    private var graphJob: Job? = null
    private var previewJob: Job? = null
    private var request = 0L

    fun open(conversationId: String) {
        if (id == conversationId) return
        if (id != null) closePreview()
        id = conversationId
        graphJob?.cancel()
        graphJob = viewModelScope.launch {
            try {
                repo.observeBranchTree(conversationId).collectLatest { snapshot ->
                    if (snapshot == null) { _state.value = BranchTreeState(error = "聊天室已不存在 / 聊天室已不存在", loading = false); return@collectLatest }
                    _state.value = withContext(Dispatchers.Default) {
                        BranchTreeState(snapshot, buildBranchTree(snapshot), loading = false, rowsByBranch = snapshot.rows.groupBy { it.branchId })
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.value = BranchTreeState(error = e.message ?: "載入失敗 / 加载失败", loading = false) }
        }
    }

    fun retry() { val current = id ?: return; id = null; open(current) }
    fun toggle(nodeId: String) {
        val next = _expanded.value.toMutableSet()
        if (!next.add(nodeId)) next.remove(nodeId)
        _expanded.value = next; saved["expanded"] = ArrayList(next)
    }

    fun select(branchId: String, messageId: String?) {
        val snapshot = _state.value.snapshot ?: return
        if (snapshot.routes.none { it.id == branchId }) return
        selectionSnapshot = snapshot
        _preview.value = BranchPreviewState(branchId, messageId)
        loadPreview(null)
    }

    fun loadPreview(offset: Int?) {
        val selection = _preview.value ?: return
        val conversationId = id ?: return
        val token = ++request
        previewJob?.cancel()
        _preview.value = selection.copy(loading = true, error = null)
        previewJob = viewModelScope.launch {
            try {
                val page = repo.getBranchPreview(conversationId, selection.branchId, selection.messageId, offset)
                    ?: error("路線已不存在 / 路线已不存在")
                if (request == token && id == conversationId && _preview.value?.branchId == selection.branchId)
                    _preview.value = selection.copy(page = page, loading = false)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (request == token) _preview.value = selection.copy(loading = false, error = e.message) }
        }
    }

    fun closePreview() { request++; previewJob?.cancel(); _preview.value = null; selectionSnapshot = null }

    fun switchFailed(result: BranchSwitchResult) {
        val selection = _preview.value ?: return
        _preview.value = selection.copy(error = when (result) {
            BranchSwitchResult.HISTORY_CHANGED -> "歷史已變更，請關閉預覽並重新選擇路線。 / 历史已变更，请关闭预览并重新选择路线。"
            BranchSwitchResult.TARGET_MISSING -> "目標路線已不存在。 / 目标路线已不存在。"
            else -> "路線資料不完整或仍在生成，無法切換。 / 路线数据不完整或仍在生成，无法切换。"
        })
        retry()
    }
}
