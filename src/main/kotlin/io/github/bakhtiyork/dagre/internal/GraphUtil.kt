// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre.internal

import io.github.bakhtiyork.dagre.*
import kotlin.math.abs
import kotlin.math.max

internal fun addDummyNode(
    graph: DagreGraph,
    type: DummyType,
    width: Double = 0.0,
    height: Double = 0.0,
    rank: Int = 0,
    edgeSource: String? = null,
    edgeTarget: String? = null,
    edgeName: String? = null,
    prefix: String = "_d",
): String {
    val id = graph.uniqueId(prefix)
    graph.setNode(id, DagreNodeLabel(width, height).also {
        it.dummy = type
        it.rank = rank
        it.edgeSource = edgeSource
        it.edgeTarget = edgeTarget
        it.edgeName = edgeName
    })
    return id
}

internal fun addBorderNode(graph: DagreGraph, prefix: String, rank: Int? = null, order: Int? = null): String {
    val id = graph.uniqueId(prefix)
    graph.setNode(id, DagreNodeLabel().also {
        it.dummy = DummyType.BORDER
        if (rank != null) it.rank = rank
        if (order != null) it.order = order
    })
    return id
}

internal fun simplify(graph: DagreGraph): DagreGraph {
    val result = DagreGraph(GraphOptions(graph.isDirected, multigraph = false, compound = false))
    graph.nodes.forEach { result.setNode(it, graph.node(it)) }
    graph.edges.forEach { edge ->
        val original = graph.edge(edge.id) ?: return@forEach
        val existing = result.edge(edge.source, edge.target)
        if (existing != null) {
            existing.weight += original.weight
            existing.minLength = max(existing.minLength, original.minLength)
        } else {
            result.setEdge(edge.source, edge.target, DagreEdgeLabel(original.minLength, original.weight))
        }
    }
    return result
}

internal fun asNonCompoundGraph(graph: DagreGraph): DagreGraph {
    val result = DagreGraph(GraphOptions(graph.isDirected, graph.isMultigraph, compound = false))
    graph.nodes.filter(graph::isLeaf).forEach { result.setNode(it, graph.node(it)) }
    graph.edges.filter { result.hasNode(it.source) && result.hasNode(it.target) }
        .forEach { result.setEdge(it.source, it.target, graph.edge(it.id), it.name) }
    return result
}

internal fun buildLayerMatrix(graph: DagreGraph): List<List<String>> {
    val maxRank = graph.nodes.maxOfOrNull { graph.node(it)?.rank ?: 0 } ?: return emptyList()
    if (maxRank < 0) return emptyList()
    val layers = MutableList(maxRank + 1) { mutableListOf<String>() }
    graph.nodes.forEach { id ->
        val rank = graph.node(id)?.rank ?: return@forEach
        if (rank in layers.indices) layers[rank] += id
    }
    layers.forEach { layer -> layer.sortBy { graph.node(it)?.order ?: 0 } }
    return layers
}

internal fun maxRank(graph: DagreGraph): Int = graph.nodes.maxOfOrNull { graph.node(it)?.rank ?: Int.MIN_VALUE } ?: Int.MIN_VALUE

internal fun normalizeRanks(graph: DagreGraph) {
    val minRank = graph.nodes.mapNotNull { graph.node(it) }
        .filter { it.dummy == null || it.dummy == DummyType.EDGE || it.dummy == DummyType.EDGE_LABEL }
        .minOfOrNull { it.rank } ?: return
    graph.nodes.forEach { graph.node(it)?.run { rank -= minRank } }
}

internal fun removeEmptyRanks(graph: DagreGraph, nodeRankFactor: Int) {
    if (graph.isCompound && graph.nodes.any { !graph.children(it).isNullOrEmpty() }) return
    val ranks = graph.nodes.mapNotNull { graph.node(it)?.rank }
    val offset = ranks.minOrNull() ?: 0
    val layers = mutableListOf<MutableList<String>?>()
    graph.nodes.forEach { id ->
        val rank = (graph.node(id)?.rank ?: return@forEach) - offset
        if (rank < 0) return@forEach
        while (layers.size <= rank) layers += null
        if (layers[rank] == null) layers[rank] = mutableListOf()
        layers[rank]!!.add(id)
    }
    var delta = 0
    layers.forEachIndexed { index, layer ->
        if (layer == null) {
            if (nodeRankFactor != 0 && index % nodeRankFactor != 0) delta--
        } else if (delta != 0) {
            layer.forEach { graph.node(it)!!.rank += delta }
        }
    }
}

internal fun successorWeights(graph: DagreGraph): Map<String, Map<String, Int>> = graph.nodes.associateWith { id ->
    buildMap { graph.outEdges(id).orEmpty().forEach { edge ->
        put(edge.target, getOrDefault(edge.target, 0) + (graph.edge(edge.id)?.weight ?: 1))
    } }
}

internal fun predecessorWeights(graph: DagreGraph): Map<String, Map<String, Int>> = graph.nodes.associateWith { id ->
    buildMap { graph.inEdges(id).orEmpty().forEach { edge ->
        put(edge.source, getOrDefault(edge.source, 0) + (graph.edge(edge.id)?.weight ?: 1))
    } }
}

internal fun intersectRect(x: Double, y: Double, width: Double, height: Double, point: Point): Point {
    val dx = point.x - x
    val dy = point.y - y
    if (dx == 0.0 && dy == 0.0) throw GraphException.RectangleIntersectionAtCenter()
    var halfWidth = width / 2
    var halfHeight = height / 2
    val sx: Double
    val sy: Double
    if (abs(dy) * halfWidth > abs(dx) * halfHeight) {
        if (dy < 0) halfHeight = -halfHeight
        sx = halfHeight * dx / dy
        sy = halfHeight
    } else {
        if (dx < 0) halfWidth = -halfWidth
        sx = halfWidth
        sy = halfWidth * dy / dx
    }
    return Point(x + sx, y + sy)
}
