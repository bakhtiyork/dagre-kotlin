// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre.internal

import io.github.bakhtiyork.dagre.*

internal fun normalizeEdges(graph: DagreGraph): List<String> {
    val chains = mutableListOf<String>()
    graph.edges.toList().forEach { edge ->
        val sourceRank = graph.node(edge.source)?.rank ?: return@forEach
        val targetRank = graph.node(edge.target)?.rank ?: return@forEach
        val label = graph.edge(edge.id) ?: return@forEach
        if (targetRank == sourceRank + 1) return@forEach
        graph.removeEdge(edge.id)
        label.points = emptyList()
        val labelRank = label.labelRank ?: (sourceRank + targetRank) / 2
        var previous = edge.source
        var first = true
        for (rank in sourceRank + 1 until targetRank) {
            val dummy = addDummyNode(
                graph, DummyType.EDGE, rank = rank,
                edgeSource = edge.source, edgeTarget = edge.target, edgeName = edge.name,
            )
            graph.node(dummy)!!.also {
                it.edgeLabel = label
                it.edgeObject = edge
                if (rank == labelRank && (label.width > 0 || label.height > 0)) {
                    it.dummy = DummyType.EDGE_LABEL
                    it.width = label.width
                    it.height = label.height
                    it.labelPosition = label.labelPosition
                }
            }
            graph.setEdge(previous, dummy, DagreEdgeLabel(weight = label.weight), edge.name)
            if (first) { chains += dummy; first = false }
            previous = dummy
        }
        graph.setEdge(previous, edge.target, DagreEdgeLabel(weight = label.weight), edge.name)
    }
    return chains
}

internal fun undoNormalize(graph: DagreGraph, chains: List<String>) {
    chains.forEach { first ->
        val firstLabel = graph.node(first) ?: return@forEach
        val original = firstLabel.edgeLabel ?: return@forEach
        val edge = firstLabel.edgeObject ?: return@forEach
        graph.setEdge(edge.source, edge.target, original, edge.name)
        val points = original.points.toMutableList()
        var current = first
        while (true) {
            val node = graph.node(current) ?: break
            if (node.dummy == null) break
            val next = graph.successors(current)?.firstOrNull() ?: break
            points += Point(node.x, node.y)
            if (node.dummy == DummyType.EDGE_LABEL) {
                original.x = node.x
                original.y = node.y
                original.width = node.width
                original.height = node.height
                original.hasLabelPosition = true
            }
            graph.removeNode(current)
            current = next
        }
        original.points = points
    }
}
