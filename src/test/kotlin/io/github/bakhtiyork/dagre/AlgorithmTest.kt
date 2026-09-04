package io.github.bakhtiyork.dagre

import io.github.bakhtiyork.dagre.internal.*
import kotlin.test.*

class AlgorithmTest {
    @Test fun `longest path ranks a chain and respects minimum length`() {
        val graph = graph("a" to "b", "b" to "c")
        graph.edge("a", "b")!!.minLength = 2
        longestPath(graph)
        normalizeRanks(graph)
        assertEquals(0, graph.node("a")!!.rank)
        assertEquals(2, graph.node("b")!!.rank)
        assertEquals(3, graph.node("c")!!.rank)
    }

    @Test fun `network simplex ranks a diamond`() {
        val graph = graph("a" to "b", "a" to "c", "b" to "d", "c" to "d")
        networkSimplex(graph)
        normalizeRanks(graph)
        assertEquals(0, graph.node("a")!!.rank)
        assertEquals(1, graph.node("b")!!.rank)
        assertEquals(1, graph.node("c")!!.rank)
        assertEquals(2, graph.node("d")!!.rank)
    }

    @Test fun `normalization inserts and removes a dummy chain`() {
        val graph = graph("a" to "b")
        graph.node("a")!!.rank = 0
        graph.node("b")!!.rank = 4
        val label = graph.edge("a", "b")!!
        val chains = normalizeEdges(graph)
        assertEquals(5, graph.nodeCount)
        assertEquals(1, chains.size)
        graph.nodes.forEachIndexed { index, id -> graph.node(id)!!.run { x = index.toDouble(); y = rank.toDouble() } }
        undoNormalize(graph, chains)
        assertEquals(2, graph.nodeCount)
        assertSame(label, graph.edge("a", "b"))
        assertEquals(3, label.points.size)
    }

    @Test fun `cross count detects a crossing`() {
        val graph = graph("a" to "d", "b" to "c")
        listOf("a", "b").forEachIndexed { index, id -> graph.node(id)!!.apply { rank = 0; order = index } }
        listOf("c", "d").forEachIndexed { index, id -> graph.node(id)!!.apply { rank = 1; order = index } }
        assertEquals(1, crossCount(graph, listOf(listOf("a", "b"), listOf("c", "d"))))
    }

    @Test fun `custom ranker and order are honored`() {
        val graph = graph("a" to "b")
        rank(graph, RankingAlgorithm.NONE) { it.node("a")!!.rank = 10; it.node("b")!!.rank = 20 }
        assertEquals(10, graph.node("a")!!.rank)
        order(graph) { g, _ -> g.node("a")!!.order = 1; g.node("b")!!.order = 0 }
        assertEquals(1, graph.node("a")!!.order)
    }

    @Test fun `rectangle intersection finds boundary and rejects center`() {
        assertEquals(Point(10.0, 5.0), intersectRect(5.0, 5.0, 10.0, 10.0, Point(20.0, 5.0)))
        assertFailsWith<GraphException.RectangleIntersectionAtCenter> {
            intersectRect(5.0, 5.0, 10.0, 10.0, Point(5.0, 5.0))
        }
    }

    private fun graph(vararg edges: Pair<String, String>): DagreGraph = DagreGraph(GraphOptions(multigraph = true)).apply {
        edges.flatMap { listOf(it.first, it.second) }.distinct().forEach { setNode(it, DagreNodeLabel(40.0, 20.0)) }
        edges.forEach { setEdge(it.first, it.second, DagreEdgeLabel()) }
    }
}
