package com.aichat.app.domain

import com.aichat.app.data.*
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BranchElementKey(val messageId: String, val versionId: String, val excluded: Boolean)
fun BranchTreeRow.elementKey() = BranchElementKey(messageId, versionId, excluded)

data class BranchTreeNode(
    val id: String, val parentId: String?, val children: List<String>, val rows: List<BranchTreeRow>,
    val branchIds: List<String>, val terminalBranchIds: List<String>,
)

data class BranchTree(val nodes: Map<String, BranchTreeNode>, val rootId: String = "root") {
    fun visible(expanded: Set<String>, active: String): List<BranchTreeNode> {
        val result = ArrayList<BranchTreeNode>()
        val pending = ArrayDeque<String>(); pending.add(rootId)
        while (pending.isNotEmpty()) {
            val n = nodes.getValue(pending.removeLast()); result.add(n)
            if (n.id == rootId || n.id in expanded || active in n.branchIds) n.children.asReversed().forEach(pending::add)
        }
        return result
    }

    fun currentFocus(active: String): String = nodes.values.lastOrNull {
        active in it.branchIds && it.children.size > 1
    }?.id ?: nodes.values.firstOrNull { active in it.terminalBranchIds }?.id ?: rootId
}

private class TrieNode(val id: String, val row: BranchTreeRow?) {
    val children = LinkedHashMap<BranchElementKey, TrieNode>()
    val branches = LinkedHashSet<String>()
    val endpoints = ArrayList<String>()
}

/** Iterative prefix trie: identity is a route element, never its prose or sourceBranchId. */
suspend fun buildBranchTree(snapshot: BranchTreeSnapshot): BranchTree {
    val context = currentCoroutineContext()
    val root = TrieNode("root", null)
    val rows = snapshot.rows.groupBy { it.branchId }
    var visited = 0
    snapshot.routes.sortedWith(compareBy<BranchTreeRoute> { it.createdAt }.thenBy { it.id }).forEach { branch ->
        var node = root; node.branches.add(branch.id)
        rows[branch.id].orEmpty().forEach { row ->
            if (++visited % 128 == 0) context.ensureActive()
            val parent = node
            node = node.children.getOrPut(row.elementKey()) {
                val identity = "${parent.id}\u0000${row.messageId}\u0000${row.versionId}\u0000${row.excluded}"
                TrieNode(UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString(), row)
            }
            node.branches.add(branch.id)
        }
        node.endpoints.add(branch.id)
    }
    val output = LinkedHashMap<String, BranchTreeNode>()
    val pending = ArrayDeque<Triple<TrieNode, String?, Boolean>>()
    pending.add(Triple(root, null, false))
    while (pending.isNotEmpty()) {
        context.ensureActive()
        val (head, parentId, preserveFirst) = pending.removeLast()
        var end = head
        val segment = ArrayList<BranchTreeRow>()
        head.row?.let(segment::add)
        while (end !== root && !preserveFirst && end.endpoints.isEmpty() && end.children.size == 1 && end.row?.deleted != true) {
            val next = end.children.values.first()
            if (next.row?.deleted == true) break
            end = next; end.row?.let(segment::add)
        }
        val children = end.children.values.toList()
        output[head.id] = BranchTreeNode(head.id, parentId, children.map { it.id }, segment,
            head.branches.toList(), end.endpoints.toList())
        children.asReversed().forEach { pending.add(Triple(it, head.id, children.size > 1)) }
    }
    return BranchTree(output)
}

/** Explicit node wins; endpoint selection falls back to the first route difference. */
fun branchLocationMessage(snapshot: BranchTreeSnapshot, target: String, explicit: String?): String? {
    val all = snapshot.rows.groupBy { it.branchId }
    val rows = all[target].orEmpty()
    val old = all[snapshot.activeBranchId].orEmpty()
    val index = explicit?.let { id -> rows.indexOfFirst { it.messageId == id }.takeIf { it >= 0 } }
        ?: rows.indices.firstOrNull { it >= old.size || rows[it].elementKey() != old[it].elementKey() }
        ?: rows.lastIndex
    return rows.drop(index.coerceAtLeast(0)).firstOrNull { !it.deleted }?.messageId
        ?: rows.take(index.coerceAtLeast(0)).lastOrNull { !it.deleted }?.messageId
}
