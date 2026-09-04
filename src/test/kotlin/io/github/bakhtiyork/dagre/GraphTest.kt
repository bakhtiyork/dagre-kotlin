package io.github.bakhtiyork.dagre

import kotlin.test.*

class GraphTest {
    @Test fun `nodes retain insertion order and labels`() {
        val graph = DagreGraph()
        graph.setNode("b", DagreNodeLabel(20.0, 10.0))
        graph.setNode("a", DagreNodeLabel(30.0, 15.0))
        assertEquals(listOf("b", "a"), graph.nodes)
        assertEquals(30.0, graph.node("a")?.width)
    }

    @Test fun `removing a node removes incident edges`() {
        val graph = graph()
        graph.edge("a", "b")
        graph.removeNode("a")
        assertFalse(graph.hasNode("a"))
        assertEquals(0, graph.edgeCount)
    }

    @Test fun `sources sinks predecessors and successors`() {
        val graph = graph()
        graph.edge("a", "b").edge("a", "c").edge("b", "c")
        assertEquals(listOf("a"), graph.sources)
        assertEquals(listOf("c"), graph.sinks)
        assertEquals(listOf("b", "c"), graph.successors("a"))
        assertEquals(listOf("a", "b"), graph.predecessors("c"))
    }

    @Test fun `multigraph keeps named parallel edges`() {
        val graph = DagreGraph(GraphOptions(multigraph = true))
        graph.setEdge("a", "b", DagreEdgeLabel(weight = 1), "one")
        graph.setEdge("a", "b", DagreEdgeLabel(weight = 2), "two")
        assertEquals(2, graph.edgeCount)
        assertEquals(2, graph.edge("a", "b", "two")?.weight)
    }

    @Test fun `named edge requires multigraph`() {
        assertFailsWith<GraphException.NamedEdgeRequiresMultigraph> {
            DagreGraph().setEdge("a", "b", DagreEdgeLabel(), "named")
        }
    }

    @Test fun `compound relationships and ancestry`() {
        val graph = DagreGraph(GraphOptions(compound = true))
        listOf("root", "a", "b", "c", "d").forEach { graph.setNode(it, DagreNodeLabel()) }
        graph.setParent("a", "root")
        graph.setParent("b", "root")
        graph.setParent("c", "a")
        graph.setParent("d", "a")
        assertEquals(listOf("a", "b"), graph.children("root"))
        assertEquals(setOf("a", "b", "c", "d"), graph.descendants("root"))
        assertEquals("a", graph.lowestCommonAncestor("c", "d"))
        assertEquals("root", graph.lowestCommonAncestor("c", "b"))
    }

    @Test fun `compound cycles are rejected`() {
        val graph = DagreGraph(GraphOptions(compound = true))
        graph.setParent("b", "a")
        graph.setParent("c", "b")
        val error = assertFailsWith<GraphException.ParentCycle> { graph.setParent("a", "c") }
        assertEquals("c", error.parent)
        assertEquals("a", error.child)
        assertFailsWith<GraphException.ParentCycle> { graph.setParent("a", "a") }
    }

    @Test fun `undirected edges normalize identity`() {
        val graph = Graph<String, String>(GraphOptions(directed = false))
        graph.setEdge("z", "a", "label")
        assertTrue(graph.hasEdge("a", "z"))
        assertEquals("label", graph.edge("z", "a"))
    }

    private fun graph() = GraphBuilder()

    private class GraphBuilder {
        val value = DagreGraph()
        fun edge(source: String, target: String): GraphBuilder = apply {
            if (!value.hasNode(source)) value.setNode(source, DagreNodeLabel())
            if (!value.hasNode(target)) value.setNode(target, DagreNodeLabel())
            value.setEdge(source, target, DagreEdgeLabel())
        }
        fun removeNode(id: String) = value.removeNode(id)
        fun hasNode(id: String) = value.hasNode(id)
        fun hasEdge(source: String, target: String) = value.hasEdge(source, target)
        val edgeCount get() = value.edgeCount
        val sources get() = value.sources
        val sinks get() = value.sinks
        fun successors(id: String) = value.successors(id)
        fun predecessors(id: String) = value.predecessors(id)
    }
}
