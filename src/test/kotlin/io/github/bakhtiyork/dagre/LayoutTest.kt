package io.github.bakhtiyork.dagre

import kotlin.math.abs
import kotlin.test.*

class LayoutTest {
    @Test fun `matches Swift golden coordinates for a chain`() {
        val graph = DagreGraph(GraphOptions(multigraph = true)).apply {
            listOf("a", "b", "c").forEach { setNode(it, DagreNodeLabel(50.0, 30.0)) }
            setEdge("a", "b", DagreEdgeLabel())
            setEdge("b", "c", DagreEdgeLabel())
        }
        val result = graph.layout(LayoutOptions(nodeSeparation = 20.0, rankSeparation = 40.0))
        assertEquals(LayoutResult(50.0, 170.0), result)
        assertEquals(listOf(Point(25.0, 30.0), Point(25.0, 50.0), Point(25.0, 70.0)), graph.edge("a", "b")!!.points)
        assertEquals(listOf(0, 2, 4), listOf("a", "b", "c").map { graph.node(it)!!.rank })
    }

    @Test fun `matches Swift golden coordinates for a diamond`() {
        val graph = DagreGraph(GraphOptions(multigraph = true)).apply {
            listOf("a", "b", "c", "d").forEach { setNode(it, DagreNodeLabel(50.0, 30.0)) }
            listOf("a" to "b", "a" to "c", "b" to "d", "c" to "d").forEach { setEdge(it.first, it.second, DagreEdgeLabel()) }
        }
        assertEquals(LayoutResult(150.0, 190.0), graph.layout())
        assertEquals(75.0, graph.node("a")!!.x)
        assertEquals(25.0, graph.node("b")!!.x)
        assertEquals(125.0, graph.node("c")!!.x)
        assertEquals(75.0, graph.node("d")!!.x)
        assertEquals(listOf(Point(56.25, 30.0), Point(25.0, 55.0), Point(25.0, 80.0)), graph.edge("a", "b")!!.points)
    }

    @Test fun `linear chain is laid out top to bottom`() {
        val graph = chain(4)
        val result = graph.layout(LayoutOptions(nodeSeparation = 20.0, rankSeparation = 40.0))
        assertTrue(result.width > 0 && result.height > 0)
        assertTrue(graph.node("n0")!!.y < graph.node("n1")!!.y)
        assertTrue(graph.node("n1")!!.y < graph.node("n2")!!.y)
        assertValid(graph)
    }

    @Test fun `all directions orient a chain correctly`() {
        RankDirection.entries.forEach { direction ->
            val graph = chain(3)
            graph.layout(LayoutOptions(rankDirection = direction))
            val a = graph.node("n0")!!
            val c = graph.node("n2")!!
            when (direction) {
                RankDirection.TOP_TO_BOTTOM -> assertTrue(a.y < c.y)
                RankDirection.BOTTOM_TO_TOP -> assertTrue(a.y > c.y)
                RankDirection.LEFT_TO_RIGHT -> assertTrue(a.x < c.x)
                RankDirection.RIGHT_TO_LEFT -> assertTrue(a.x > c.x)
            }
            assertValid(graph)
        }
    }

    @Test fun `diamond shares ranks without overlap`() {
        val graph = DagreGraph(GraphOptions(multigraph = true))
        listOf("a", "b", "c", "d").forEach { graph.setNode(it, DagreNodeLabel(50.0, 30.0)) }
        listOf("a" to "b", "a" to "c", "b" to "d", "c" to "d").forEach { graph.setEdge(it.first, it.second, DagreEdgeLabel()) }
        graph.layout()
        assertEquals(graph.node("b")!!.y, graph.node("c")!!.y, 1e-9)
        assertNotEquals(graph.node("b")!!.x, graph.node("c")!!.x)
        assertValid(graph)
    }

    @Test fun `cycle is restored after layout`() {
        val graph = DagreGraph(GraphOptions(multigraph = true))
        listOf("a", "b", "c").forEach { graph.setNode(it, DagreNodeLabel(40.0, 30.0)) }
        graph.setEdge("a", "b", DagreEdgeLabel())
        graph.setEdge("b", "c", DagreEdgeLabel())
        graph.setEdge("c", "a", DagreEdgeLabel())
        graph.layout()
        assertEquals(3, graph.edgeCount)
        assertTrue(graph.hasEdge("c", "a"))
        assertValid(graph)
    }

    @Test fun `self edge receives routed points`() {
        val graph = DagreGraph(GraphOptions(multigraph = true))
        graph.setNode("a", DagreNodeLabel(60.0, 30.0))
        graph.setEdge("a", "a", DagreEdgeLabel(width = 20.0, height = 10.0), "self")
        graph.layout()
        assertTrue(graph.edge("a", "a", "self")!!.points.size >= 7)
    }

    @Test fun `parallel and opposite edges survive layout`() {
        val graph = DagreGraph(GraphOptions(multigraph = true))
        listOf("a", "b").forEach { graph.setNode(it, DagreNodeLabel(40.0, 20.0)) }
        graph.setEdge("a", "b", DagreEdgeLabel(), "one")
        graph.setEdge("a", "b", DagreEdgeLabel(), "two")
        graph.setEdge("b", "a", DagreEdgeLabel(), "back")
        graph.layout()
        assertEquals(3, graph.edgeCount)
        graph.edges.forEach { assertTrue(graph.edge(it.id)!!.points.size >= 2) }
    }

    @Test fun `edge label positions and margins affect outputs`() {
        LabelPosition.entries.forEach { position ->
            val graph = chain(2)
            graph.edge("n0", "n1")!!.apply { width = 30.0; height = 12.0; labelPosition = position }
            val result = graph.layout(LayoutOptions(marginX = 11.0, marginY = 13.0))
            val edge = graph.edge("n0", "n1")!!
            assertTrue(edge.x.isFinite() && edge.y.isFinite())
            assertTrue(result.width >= 22 && result.height >= 26)
            assertTrue(graph.nodes.all { graph.node(it)!!.x >= 11 && graph.node(it)!!.y >= 13 })
        }
    }

    @Test fun `compound node encloses children`() {
        val graph = DagreGraph(GraphOptions(multigraph = true, compound = true))
        graph.setNode("outside", DagreNodeLabel(50.0, 30.0))
        graph.setNode("group", DagreNodeLabel())
        graph.setNode("one", DagreNodeLabel(50.0, 30.0))
        graph.setNode("two", DagreNodeLabel(50.0, 30.0))
        graph.setParent("one", "group")
        graph.setParent("two", "group")
        graph.setEdge("outside", "one", DagreEdgeLabel())
        graph.setEdge("one", "two", DagreEdgeLabel())
        graph.layout()
        val group = graph.node("group")!!
        assertTrue(group.width > 0 && group.height > 0)
        assertValid(graph, checkOverlap = false)
    }

    @Test fun `nested compound graph lays out and computes both containers`() {
        val graph = DagreGraph(GraphOptions(multigraph = true, compound = true))
        listOf("outer", "inner").forEach { graph.setNode(it, DagreNodeLabel()) }
        listOf("outside", "a", "b", "c").forEach { graph.setNode(it, DagreNodeLabel(40.0, 24.0)) }
        graph.setParent("inner", "outer")
        graph.setParent("a", "inner")
        graph.setParent("b", "inner")
        graph.setParent("c", "outer")
        graph.setEdge("outside", "a", DagreEdgeLabel())
        graph.setEdge("a", "b", DagreEdgeLabel())
        graph.setEdge("b", "c", DagreEdgeLabel())
        graph.layout()
        assertTrue(graph.node("inner")!!.width > 0 && graph.node("inner")!!.height > 0)
        assertTrue(graph.node("outer")!!.width >= graph.node("inner")!!.width)
        assertTrue(graph.node("outer")!!.height >= graph.node("inner")!!.height)
        assertValid(graph, checkOverlap = false)
    }

    @Test fun `wide tree keeps children on one rank without overlap`() {
        val graph = DagreGraph(GraphOptions(multigraph = true))
        graph.setNode("root", DagreNodeLabel(60.0, 30.0))
        repeat(10) { index ->
            graph.setNode("child$index", DagreNodeLabel(50.0, 30.0))
            graph.setEdge("root", "child$index", DagreEdgeLabel())
        }
        graph.layout(LayoutOptions(nodeSeparation = 15.0))
        val childY = graph.node("child0")!!.y
        repeat(10) { assertEquals(childY, graph.node("child$it")!!.y, 1e-9) }
        assertValid(graph)
    }

    @Test fun `layout options and input configuration are reusable`() {
        val graph = chain(3)
        val options = LayoutOptions(rankSeparation = 80.0)
        val edge = graph.edge("n0", "n1")!!
        graph.layout(options)
        val first = graph.nodes.map { graph.node(it)!!.x to graph.node(it)!!.y }
        graph.layout(options)
        assertEquals(first, graph.nodes.map { graph.node(it)!!.x to graph.node(it)!!.y })
        assertEquals(80.0, options.rankSeparation)
        assertEquals(1, edge.minLength)
    }

    @Test fun `empty and single-node graphs have finite bounds`() {
        assertEquals(LayoutResult(4.0, 6.0), DagreGraph().layout(LayoutOptions(marginX = 2.0, marginY = 3.0)))
        val graph = DagreGraph().apply { setNode("a", DagreNodeLabel(40.0, 20.0)) }
        val result = graph.layout()
        assertEquals(40.0, result.width)
        assertEquals(20.0, result.height)
    }

    @Test fun `all alignments produce valid coordinates`() {
        Alignment.entries.forEach { alignment ->
            val graph = chain(4)
            graph.layout(LayoutOptions(alignment = alignment))
            assertValid(graph)
        }
    }

    private fun chain(size: Int): DagreGraph = DagreGraph(GraphOptions(multigraph = true)).apply {
        repeat(size) { setNode("n$it", DagreNodeLabel(50.0, 30.0)) }
        repeat(size - 1) { setEdge("n$it", "n${it + 1}", DagreEdgeLabel()) }
    }

    private fun assertValid(graph: DagreGraph, checkOverlap: Boolean = true) {
        graph.nodes.forEach { id ->
            val node = graph.node(id)!!
            assertTrue(node.x.isFinite() && node.y.isFinite(), "$id has invalid coordinates")
            assertTrue(node.x >= 0 && node.y >= 0, "$id has negative coordinates")
        }
        graph.edges.forEach { assertTrue(graph.edge(it.id)!!.points.size >= 2) }
        if (checkOverlap) graph.nodes.forEachIndexed { i, firstId -> graph.nodes.drop(i + 1).forEach { secondId ->
            val first = graph.node(firstId)!!
            val second = graph.node(secondId)!!
            val overlapX = abs(first.x - second.x) < (first.width + second.width) / 2 - 1e-6
            val overlapY = abs(first.y - second.y) < (first.height + second.height) / 2 - 1e-6
            assertFalse(overlapX && overlapY, "$firstId overlaps $secondId")
        } }
    }
}
