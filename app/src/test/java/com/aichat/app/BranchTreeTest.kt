package com.aichat.app

import com.aichat.app.data.*
import com.aichat.app.domain.*
import com.aichat.app.ui.layoutBranchTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureTimeMillis

class BranchTreeTest {
    private fun row(branch: String, message: String, version: String = message, excluded: Boolean = false, deleted: Boolean = false) =
        BranchTreeRow(branch, message, version, 1, excluded, deleted, "user", MessageKind.CHAT, 1,
            MessageVersionStatus.COMPLETE, MessageVersionSource.ORIGINAL, "same text", null, true)
    private fun snapshot(vararg paths: Pair<String, List<BranchTreeRow>>) = BranchTreeSnapshot("c", "story", paths.first().first, 0,
        paths.mapIndexed { i, p -> BranchTreeRoute(p.first, i.toLong(), null, null, null, false) }, paths.flatMap { it.second })

    @Test fun sharedPrefixNestedSplitAndPrefixEndpointArePreserved() = runBlocking {
        val s = snapshot("a" to listOf(row("a", "start"), row("a", "cat")),
            "b" to listOf(row("b", "start"), row("b", "cat", "white"), row("b", "hut")),
            "c" to listOf(row("c", "start")))
        val tree = buildBranchTree(s)
        assertEquals(3, tree.nodes.values.sumOf { it.terminalBranchIds.size })
        assertEquals(1, tree.nodes.values.flatMap { it.rows }.count { it.messageId == "start" })
        assertTrue(tree.nodes.values.any { it.children.size == 2 })
        assertTrue(tree.nodes.values.any { "c" in it.terminalBranchIds && it.children.isNotEmpty() })
    }

    @Test fun identicalProseDifferentVersionOrExclusionNeverMerges() = runBlocking {
        val tree = buildBranchTree(snapshot("a" to listOf(row("a", "x", "v1")),
            "b" to listOf(row("b", "x", "v2")), "c" to listOf(row("c", "x", "v1", true))))
        assertEquals(3, tree.nodes.getValue("root").children.size)
    }

    @Test fun identicalSequencesKeepEveryRealBranchAndStableIds() = runBlocking {
        val s = snapshot("a" to listOf(row("a", "x")), "b" to listOf(row("b", "x")))
        val tree = buildBranchTree(s)
        assertEquals(listOf("a", "b"), tree.nodes.values.single { it.rows.isNotEmpty() }.terminalBranchIds)
        assertEquals(tree, buildBranchTree(s.copy(routes = s.routes.reversed(), rows = s.rows.reversed())))
    }

    @Test fun deletedMarkerIsNotCompressedAwayAndCurrentPathCannotCollapse() = runBlocking {
        val a = listOf(row("a", "1"), row("a", "deleted", deleted = true), row("a", "3"))
        val b = listOf(row("b", "1"), row("b", "other"), row("b", "4"))
        val tree = buildBranchTree(snapshot("a" to a, "b" to b))
        assertTrue(tree.nodes.values.any { it.rows.size == 1 && it.rows.first().deleted })
        val visible = tree.visible(emptySet(), "a")
        assertTrue(visible.any { "a" in it.terminalBranchIds })
        assertEquals(visible.map { it.id }.distinct().size, visible.size)
    }

    @Test fun explicitLocationOrFirstDifferenceSkipsDeleted() {
        val s = snapshot("a" to listOf(row("a", "1"), row("a", "2")),
            "b" to listOf(row("b", "1"), row("b", "2", "v2", deleted = true), row("b", "3")))
        assertEquals("3", branchLocationMessage(s, "b", null))
        assertEquals("1", branchLocationMessage(s, "b", "1"))
        assertNull(branchLocationMessage(s, "missing", null))
    }

    @Test fun tenThousandSinglePathIsIterativeAndCompressed() = runBlocking {
        val rows = (0 until 10_000).map { row("a", "m$it") }
        lateinit var tree: BranchTree
        val elapsed = measureTimeMillis { tree = buildBranchTree(snapshot("a" to rows)) }
        assertEquals(2, tree.nodes.size)
        assertEquals(10_000, tree.nodes.values.sumOf { it.rows.size })
        println("BranchTree 10000 single path: ${elapsed}ms (${System.getProperty("os.arch")})")
    }

    @Test fun hundredRoutesHundredThousandRelationsAndThousandShortBranches() = runBlocking {
        for ((branches, length) in listOf(100 to 1000, 1000 to 3)) {
            val paths = (0 until branches).map { b -> "b$b" to (0 until length).map { m -> row("b$b", if (m < length - 1) "shared$m" else "leaf$b") } }
            lateinit var tree: BranchTree
            val elapsed = measureTimeMillis { tree = buildBranchTree(snapshot(*paths.toTypedArray())) }
            assertEquals(branches, tree.nodes.values.sumOf { it.terminalBranchIds.size })
            val nodes = tree.visible(tree.nodes.keys, "b0")
            val layout = layoutBranchTree(nodes, nodes.associate { it.id to 100f })
            val levels = layout.bounds.values.groupBy { it.y }
            levels.values.forEach { rects -> rects.sortedBy { it.x }.zipWithNext().forEach { (a, b) -> assertTrue(a.x + a.width + 23.9f <= b.x) } }
            println("BranchTree $branches routes ${branches * length} relations: ${elapsed}ms")
        }
    }
}
