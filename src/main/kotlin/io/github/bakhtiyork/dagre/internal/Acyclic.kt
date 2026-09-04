// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre.internal

import io.github.bakhtiyork.dagre.*

internal fun makeAcyclic(graph: DagreGraph, algorithm: AcyclicAlgorithm) {
    val feedback = when (algorithm) {
        AcyclicAlgorithm.DFS -> dfsFeedbackArcSet(graph)
        AcyclicAlgorithm.GREEDY -> greedyFeedbackArcSet(graph)
    }
    feedback.forEach { reverseEdge(graph, it) }
}

internal fun undoAcyclic(graph: DagreGraph) {
    graph.edges.toList().forEach { edge ->
        val label = graph.edge(edge.id) ?: return@forEach
        if (label.reversed) {
            graph.removeEdge(edge.id)
            label.reversed = false
            val name = label.forwardName
            label.forwardName = null
            graph.setEdge(edge.target, edge.source, label, name)
        }
    }
}

private fun reverseEdge(graph: DagreGraph, edge: Edge) {
    val label = graph.edge(edge.id) ?: return
    graph.removeEdge(edge.id)
    label.forwardName = edge.name
    label.reversed = true
    graph.setEdge(edge.target, edge.source, label, graph.uniqueId("rev"))
}

private fun dfsFeedbackArcSet(graph: DagreGraph): List<Edge> {
    val result = mutableListOf<Edge>()
    val visited = hashSetOf<String>()
    val active = hashSetOf<String>()
    fun dfs(id: String) {
        if (!visited.add(id)) return
        active += id
        graph.outEdges(id).orEmpty().forEach { edge ->
            if (edge.target in active) result += edge else dfs(edge.target)
        }
        active -= id
    }
    graph.nodes.forEach(::dfs)
    return result
}

private class FasEntry(val id: String) {
    var incoming = 0
    var outgoing = 0
}

private fun greedyFeedbackArcSet(graph: DagreGraph): List<Edge> {
    if (graph.nodeCount <= 1) return emptyList()
    val aggregate = Graph<FasEntry, Int>()
    val seen = linkedSetOf<String>()
    graph.edges.forEach { seen += it.source; seen += it.target }
    seen += graph.nodes
    seen.forEach { aggregate.setNode(it, FasEntry(it)) }
    graph.edges.forEach { edge ->
        val weight = graph.edge(edge.id)?.weight ?: 1
        aggregate.setEdge(edge.source, edge.target, (aggregate.edge(edge.source, edge.target) ?: 0) + weight)
    }
    aggregate.edges.forEach { edge ->
        val weight = aggregate.edge(edge.id) ?: 0
        aggregate.node(edge.source)!!.outgoing += weight
        aggregate.node(edge.target)!!.incoming += weight
    }
    val feedbackPairs = mutableListOf<Pair<String, String>>()
    fun remove(id: String, collect: Boolean) {
        aggregate.inEdges(id).orEmpty().toList().forEach { edge ->
            if (collect) feedbackPairs += edge.source to edge.target
            aggregate.node(edge.source)?.outgoing = (aggregate.node(edge.source)?.outgoing ?: 0) - (aggregate.edge(edge.id) ?: 0)
        }
        aggregate.outEdges(id).orEmpty().toList().forEach { edge ->
            aggregate.node(edge.target)?.incoming = (aggregate.node(edge.target)?.incoming ?: 0) - (aggregate.edge(edge.id) ?: 0)
        }
        aggregate.removeNode(id)
    }
    while (aggregate.nodeCount > 0) {
        var changed: Boolean
        do {
            changed = false
            aggregate.nodes.lastOrNull { aggregate.node(it)?.outgoing == 0 }?.let { remove(it, false); changed = true }
        } while (changed)
        do {
            changed = false
            aggregate.nodes.lastOrNull { aggregate.node(it)?.incoming == 0 }?.let { remove(it, false); changed = true }
        } while (changed)
        if (aggregate.nodeCount > 0) {
            val candidate = aggregate.nodes.withIndex().maxWithOrNull(
                compareBy<IndexedValue<String>> { aggregate.node(it.value)!!.outgoing - aggregate.node(it.value)!!.incoming }
                    .thenBy { -it.index },
            )!!.value
            remove(candidate, true)
        }
    }
    return feedbackPairs.flatMap { (source, target) -> graph.outEdges(source, target).orEmpty() }
}
