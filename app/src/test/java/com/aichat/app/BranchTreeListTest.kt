package com.aichat.app

import com.aichat.app.domain.*
import com.aichat.app.ui.*
import com.aichat.app.data.*
import org.junit.Assert.*
import org.junit.Test

class BranchTreeListTest {
    private fun node(id: String, parent: String?, children: List<String> = emptyList(), endpoints: List<String> = emptyList()) =
        BranchTreeNode(id, parent, children, emptyList(), endpoints, endpoints)

    private fun fixture(): BranchTree = BranchTree(listOf(
        node("root", null, listOf("shared")),
        node("shared", "root", listOf("forest", "sea"), listOf("short")),
        node("forest", "shared", listOf("black", "white")),
        node("black", "forest", endpoints = listOf("current")),
        node("white", "forest", endpoints = listOf("white")),
        node("sea", "shared", listOf("boat")),
        node("boat", "sea", endpoints = listOf("sea")),
    ).associateBy { it.id })

    @Test fun onlyCurrentAncestorsOpenAndCountsAreNodesNotRoutes() {
        val tree = fixture()
        val expanded = branchCurrentAncestors(tree, "current")
        assertEquals(setOf("root", "shared", "forest"), expanded)
        val rows = flattenBranchList(tree, expanded, "current", branchDescendantCounts(tree))
        assertEquals(listOf("root", "shared", "forest", "black", "white", "sea"), rows.map { it.nodeId })
        assertEquals(listOf("black"), rows.filter { it.isCurrent }.map { it.nodeId })
        assertEquals(1, rows.last().hiddenDescendantCount)
        assertEquals(6, branchDescendantCounts(tree)["root"])
        val collapsed = flattenBranchList(tree, setOf("root"), "current", branchDescendantCounts(tree))
        assertEquals(5, collapsed.last().hiddenDescendantCount)
        assertEquals(7, tree.nodes.size)
    }

    @Test fun endpointParentsAndSiblingGuidesRetainIdentityAndOrder() {
        val tree = fixture()
        val rows = flattenBranchList(tree, tree.nodes.keys, "short", branchDescendantCounts(tree))
        assertEquals(listOf("shared"), rows.filter { it.isCurrent }.map { it.nodeId })
        assertEquals(listOf(false, true, true), rows.first { it.nodeId == "black" }.guides?.asFlags())
        assertEquals(listOf(false, true, false), rows.first { it.nodeId == "white" }.guides?.asFlags())
        assertEquals(listOf(false, false), rows.first { it.nodeId == "sea" }.guides?.asFlags())
        assertEquals(setOf("root"), branchCurrentAncestors(tree, "short"))
    }

    @Test fun deepTreeRetainsAllIndentationWithSharedGuidesWithoutRecursion() {
        val nodes = (0 until 20000).associate { i ->
            val id = if (i == 0) "root" else "$i"
            id to node(id, if (i == 0) null else if (i == 1) "root" else "${i - 1}",
                if (i == 19999) emptyList() else listOf("${i + 1}"), if (i == 19999) listOf("active") else emptyList())
        }
        val tree = BranchTree(nodes)
        val counts = branchDescendantCounts(tree)
        val rows = flattenBranchList(tree, branchCurrentAncestors(tree, "active"), "active", counts)
        assertEquals(20000, rows.size)
        assertEquals(19999, counts["root"])
        assertEquals(19999, rows.last().depth)
        assertEquals(19999, rows.last().guides?.depth)
        assertTrue(rows.all { (it.guides?.depth ?: 0) == it.depth })
        assertSame(rows[10000].guides, rows[10001].guides?.parent)
    }

    @Test fun presenterNeverExposesPrivateNoteAndRetainsStatuses() {
        val row = BranchTreeRow("b", "m", "v", 1, false, false, "user", MessageKind.PRIVATE_NOTE,
            2, MessageVersionStatus.PARTIAL, MessageVersionSource.ORIGINAL, "PRIVATE SECRET", null, false)
        val node = BranchTreeNode("note", "root", emptyList(), listOf(row), listOf("b"), listOf("b"))
        for (language in AppLanguage.entries) {
            val text = branchListText(node, language, mapOf("b" to 1))
            assertFalse(text.summary.contains("PRIVATE SECRET"))
            assertTrue(text.statuses.isNotBlank())
            assertEquals(BranchSpeakerTone.NEUTRAL, text.tone)
        }
    }
}
