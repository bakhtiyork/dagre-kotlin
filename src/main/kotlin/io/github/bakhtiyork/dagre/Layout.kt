// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre

import io.github.bakhtiyork.dagre.internal.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Runs the complete Dagre layout pipeline and mutates node/edge output fields. */
fun DagreGraph.layout(options: LayoutOptions = LayoutOptions()): LayoutResult {
    require(isDirected) { "Dagre layout requires a directed graph" }
    nodes.forEach { requireNotNull(node(it)) { "Node '$it' requires a DagreNodeLabel" } }
    edges.forEach { requireNotNull(edge(it.id)) { "Edge '${it.source}' -> '${it.target}' requires a DagreEdgeLabel" } }
    val internal = buildLayoutGraph(this)
    val result = runLayout(internal, options)
    copyLayoutResult(this, internal)
    return result
}

object Dagre {
    fun layout(graph: DagreGraph, options: LayoutOptions = LayoutOptions()): LayoutResult = graph.layout(options)
}

private fun buildLayoutGraph(input: DagreGraph): DagreGraph {
    val graph = DagreGraph(GraphOptions(directed = true, multigraph = true, compound = true))
    input.nodes.forEach { id -> graph.setNode(id, input.node(id)!!.layoutCopy()) }
    if (input.isCompound) input.nodes.forEach { id -> input.parent(id)?.let { graph.setParent(id, it) } }
    input.edges.forEach { edge -> graph.setEdge(edge.source, edge.target, input.edge(edge.id)!!.layoutCopy(), edge.name) }
    return graph
}

private fun copyLayoutResult(input: DagreGraph, layout: DagreGraph) {
    input.nodes.forEach { id ->
        val source = layout.node(id) ?: return@forEach
        input.node(id)?.also {
            it.x = source.x
            it.y = source.y
            it.rank = source.rank
            it.order = source.order
            if (!layout.children(id).isNullOrEmpty()) { it.width = source.width; it.height = source.height }
        }
    }
    input.edges.forEach { edge ->
        val source = layout.edge(edge.id) ?: return@forEach
        input.edge(edge.id)?.also {
            it.points = source.points.toList()
            it.x = source.x
            it.y = source.y
        }
    }
}

private data class SelfEdge(val edge: Edge, val label: DagreEdgeLabel)

private fun runLayout(graph: DagreGraph, options: LayoutOptions): LayoutResult {
    makeSpaceForEdgeLabels(graph, options.rankDirection)
    val selfEdges = removeSelfEdges(graph)
    makeAcyclic(graph, options.acyclicAlgorithm)
    val nesting = addNestingGraph(graph)
    val simple = asNonCompoundGraph(graph)
    rank(simple, options.rankingAlgorithm, options.customRanker)
    simple.nodes.forEach { id -> graph.node(id)?.rank = simple.node(id)?.rank ?: 0 }
    injectEdgeLabelProxies(graph)
    removeEmptyRanks(graph, nesting.nodeRankFactor)
    removeNestingGraph(graph, nesting)
    normalizeRanks(graph)
    assignCompoundRanks(graph)
    removeEdgeLabelProxies(graph)
    val chains = normalizeEdges(graph)
    parentDummyChains(graph, chains)
    addBorderSegments(graph)
    order(graph, options.customOrder)
    insertSelfEdges(graph, selfEdges)
    adjustCoordinateSystem(graph, options.rankDirection)
    position(
        graph,
        options.nodeSeparation,
        options.edgeSeparation,
        options.rankSeparation / 2,
        options.alignment,
    )
    positionSelfEdges(graph)
    removeBorderNodes(graph)
    undoNormalize(graph, chains)
    fixEdgeLabelCoordinates(graph)
    undoCoordinateSystem(graph, options.rankDirection)
    val result = translateGraph(graph, options.marginX, options.marginY)
    assignNodeIntersections(graph)
    graph.edges.forEach { edge ->
        graph.edge(edge.id)?.takeIf { it.reversed }?.run { points = points.asReversed() }
    }
    undoAcyclic(graph)
    return result
}

private fun makeSpaceForEdgeLabels(graph: DagreGraph, direction: RankDirection) {
    graph.edges.forEach { edge -> graph.edge(edge.id)?.also { label ->
        label.minLength *= 2
        if (label.labelPosition != LabelPosition.CENTER) {
            if (direction == RankDirection.TOP_TO_BOTTOM || direction == RankDirection.BOTTOM_TO_TOP) label.width += label.labelOffset
            else label.height += label.labelOffset
        }
    } }
}

private fun removeSelfEdges(graph: DagreGraph): Map<String, List<SelfEdge>> {
    val result = linkedMapOf<String, MutableList<SelfEdge>>()
    graph.edges.toList().filter { it.source == it.target }.forEach { edge ->
        graph.edge(edge.id)?.let { result.getOrPut(edge.source) { mutableListOf() } += SelfEdge(edge, it) }
        graph.removeEdge(edge.id)
    }
    return result
}

private fun injectEdgeLabelProxies(graph: DagreGraph) {
    graph.edges.toList().forEach { edge ->
        val label = graph.edge(edge.id) ?: return@forEach
        if (label.width > 0 && label.height > 0) {
            val source = graph.node(edge.source) ?: return@forEach
            val target = graph.node(edge.target) ?: return@forEach
            graph.setNode(graph.uniqueId("_ep"), DagreNodeLabel().also {
                it.dummy = DummyType.EDGE_PROXY
                it.rank = (target.rank - source.rank) / 2 + source.rank
                it.edgeRef = edge.id
            })
        }
    }
}

private fun removeEdgeLabelProxies(graph: DagreGraph) {
    graph.nodes.toList().forEach { id ->
        val node = graph.node(id) ?: return@forEach
        if (node.dummy == DummyType.EDGE_PROXY) {
            node.edgeRef?.let { graph.edge(it)?.labelRank = node.rank }
            graph.removeNode(id)
        }
    }
}

private fun assignCompoundRanks(graph: DagreGraph) {
    graph.nodes.forEach { id -> graph.node(id)?.also { node ->
        val top = node.borderTop?.let(graph::node)
        val bottom = node.borderBottom?.let(graph::node)
        if (top != null && bottom != null) { node.minRank = top.rank; node.maxRank = bottom.rank }
    } }
}

private fun insertSelfEdges(graph: DagreGraph, selfEdges: Map<String, List<SelfEdge>>) {
    buildLayerMatrix(graph).forEach { layer ->
        var shift = 0
        layer.forEachIndexed { index, id ->
            val node = graph.node(id) ?: return@forEachIndexed
            node.order = index + shift
            selfEdges[id].orEmpty().forEach { self ->
                shift++
                graph.setNode(graph.uniqueId("_se"), DagreNodeLabel(self.label.width, self.label.height).also {
                    it.rank = node.rank
                    it.order = index + shift
                    it.dummy = DummyType.SELF_EDGE
                    it.edgeObject = self.edge
                    it.edgeLabel = self.label
                })
            }
        }
    }
}

private fun positionSelfEdges(graph: DagreGraph) {
    graph.nodes.toList().forEach { id ->
        val dummy = graph.node(id) ?: return@forEach
        if (dummy.dummy != DummyType.SELF_EDGE) return@forEach
        val edge = dummy.edgeObject ?: return@forEach
        val label = dummy.edgeLabel ?: return@forEach
        val node = graph.node(edge.source) ?: return@forEach
        val x = node.x + node.width / 2
        val y = node.y
        val dx = dummy.x - x
        val dy = node.height / 2
        graph.setEdge(edge.source, edge.target, label, edge.name)
        graph.removeNode(id)
        label.points = listOf(
            Point(x + 2 * dx / 3, y - dy), Point(x + 5 * dx / 6, y - dy),
            Point(x + dx, y),
            Point(x + 5 * dx / 6, y + dy), Point(x + 2 * dx / 3, y + dy),
        )
        label.x = dummy.x
        label.y = dummy.y
        label.hasLabelPosition = true
    }
}

private fun removeBorderNodes(graph: DagreGraph) {
    graph.nodes.toList().forEach { id ->
        if (graph.children(id).isNullOrEmpty()) return@forEach
        val node = graph.node(id) ?: return@forEach
        val top = node.borderTop?.let(graph::node) ?: return@forEach
        val bottom = node.borderBottom?.let(graph::node) ?: return@forEach
        val rank = node.maxRank ?: node.borderLeft.keys.maxOrNull() ?: return@forEach
        val left = node.borderLeft[rank]?.let(graph::node) ?: return@forEach
        val right = node.borderRight[rank]?.let(graph::node) ?: return@forEach
        node.width = abs(right.x - left.x)
        node.height = abs(bottom.y - top.y)
        node.x = left.x + node.width / 2
        node.y = top.y + node.height / 2
    }
    graph.nodes.toList().filter { graph.node(it)?.dummy == DummyType.BORDER }.forEach(graph::removeNode)
}

private fun fixEdgeLabelCoordinates(graph: DagreGraph) {
    graph.edges.forEach { edge -> graph.edge(edge.id)?.also { label ->
        if (!label.hasLabelPosition) return@also
        when (label.labelPosition) {
            LabelPosition.LEFT -> { label.width -= label.labelOffset; label.x -= label.width / 2 + label.labelOffset }
            LabelPosition.RIGHT -> { label.width -= label.labelOffset; label.x += label.width / 2 + label.labelOffset }
            LabelPosition.CENTER -> Unit
        }
    } }
}

private fun adjustCoordinateSystem(graph: DagreGraph, direction: RankDirection) {
    if (direction != RankDirection.LEFT_TO_RIGHT && direction != RankDirection.RIGHT_TO_LEFT) return
    graph.nodes.forEach { graph.node(it)?.run { width = height.also { height = width } } }
    graph.edges.forEach { graph.edge(it.id)?.run { width = height.also { height = width } } }
}

private fun undoCoordinateSystem(graph: DagreGraph, direction: RankDirection) {
    when (direction) {
        RankDirection.TOP_TO_BOTTOM -> Unit
        RankDirection.BOTTOM_TO_TOP -> {
            graph.nodes.forEach { graph.node(it)?.run { y = -y } }
            graph.edges.forEach { graph.edge(it.id)?.run { points = points.map { p -> p.copy(y = -p.y) }; y = -y } }
        }
        RankDirection.LEFT_TO_RIGHT -> {
            graph.nodes.forEach { graph.node(it)?.run { x = y.also { y = x }; width = height.also { height = width } } }
            graph.edges.forEach { graph.edge(it.id)?.run {
                points = points.map { Point(it.y, it.x) }
                x = y.also { y = x }
                width = height.also { height = width }
            } }
        }
        RankDirection.RIGHT_TO_LEFT -> {
            graph.nodes.forEach { graph.node(it)?.run { x = (-y).also { y = x }; width = height.also { height = width } } }
            graph.edges.forEach { graph.edge(it.id)?.run {
                points = points.map { Point(-it.y, it.x) }
                x = (-y).also { y = x }
                width = height.also { height = width }
            } }
        }
    }
}

private fun translateGraph(graph: DagreGraph, marginX: Double, marginY: Double): LayoutResult {
    if (graph.nodeCount == 0) return LayoutResult(2 * marginX, 2 * marginY)
    var minimumX = Double.POSITIVE_INFINITY
    var maximumX = 0.0
    var minimumY = Double.POSITIVE_INFINITY
    var maximumY = 0.0
    fun include(x: Double, y: Double, width: Double, height: Double) {
        minimumX = min(minimumX, x - width / 2); maximumX = max(maximumX, x + width / 2)
        minimumY = min(minimumY, y - height / 2); maximumY = max(maximumY, y + height / 2)
    }
    graph.nodes.forEach { graph.node(it)?.run { include(x, y, width, height) } }
    graph.edges.forEach { graph.edge(it.id)?.takeIf { label -> label.hasLabelPosition }?.run { include(x, y, width, height) } }
    minimumX -= marginX
    minimumY -= marginY
    graph.nodes.forEach { graph.node(it)?.run { x -= minimumX; y -= minimumY } }
    graph.edges.forEach { graph.edge(it.id)?.run {
        points = points.map { Point(it.x - minimumX, it.y - minimumY) }
        if (hasLabelPosition) { x -= minimumX; y -= minimumY }
    } }
    return LayoutResult(maximumX - minimumX + marginX, maximumY - minimumY + marginY)
}

private fun assignNodeIntersections(graph: DagreGraph) {
    graph.edges.forEach { edge ->
        val label = graph.edge(edge.id) ?: return@forEach
        val source = graph.node(edge.source) ?: return@forEach
        val target = graph.node(edge.target) ?: return@forEach
        val first = label.points.firstOrNull() ?: Point(target.x, target.y)
        val last = label.points.lastOrNull() ?: Point(source.x, source.y)
        label.points = listOf(intersectRect(source.x, source.y, source.width, source.height, first)) +
            label.points + intersectRect(target.x, target.y, target.width, target.height, last)
    }
}
