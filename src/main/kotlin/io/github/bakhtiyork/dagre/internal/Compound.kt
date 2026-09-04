// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre.internal

import io.github.bakhtiyork.dagre.*
import kotlin.math.max

internal data class NestingState(val root: String, val nodeRankFactor: Int)

internal fun addNestingGraph(graph: DagreGraph): NestingState {
    val root = addDummyNode(graph, DummyType.ROOT, prefix = "_root")
    val depths = hashMapOf<String, Int>()
    fun depth(id: String, value: Int) {
        depths[id] = value
        graph.children(id).orEmpty().forEach { depth(it, value + 1) }
    }
    graph.children().orEmpty().toList().forEach { depth(it, 1) }
    val height = max(0, (depths.values.maxOrNull() ?: 0) - 1)
    val separation = 2 * height + 1
    graph.edges.forEach { graph.edge(it.id)?.run { minLength *= separation } }
    val nestingWeight = graph.edges.sumOf { graph.edge(it.id)?.weight ?: 0 } + 1
    fun visit(id: String) {
        val children = graph.children(id).orEmpty().toList()
        if (children.isEmpty()) {
            if (id != root) graph.setEdge(root, id, DagreEdgeLabel(separation, 0))
            return
        }
        val top = addBorderNode(graph, "_bt")
        val bottom = addBorderNode(graph, "_bb")
        graph.setParent(top, id)
        graph.setParent(bottom, id)
        graph.node(id)?.also { it.borderTop = top; it.borderBottom = bottom }
        children.forEach { child ->
            visit(child)
            val childNode = graph.node(child) ?: return@forEach
            val childTop = childNode.borderTop ?: child
            val childBottom = childNode.borderBottom ?: child
            val weight = if (childNode.borderTop != null) nestingWeight else 2 * nestingWeight
            val minLength = if (childTop != childBottom) 1 else height - (depths[id] ?: 0) + 1
            graph.setEdge(top, childTop, DagreEdgeLabel(minLength, weight).also { it.nestingEdge = true })
            graph.setEdge(childBottom, bottom, DagreEdgeLabel(minLength, weight).also { it.nestingEdge = true })
        }
        if (graph.parent(id) == null) graph.setEdge(root, top, DagreEdgeLabel(height + (depths[id] ?: 0), 0))
    }
    graph.children().orEmpty().toList().forEach(::visit)
    return NestingState(root, separation)
}

internal fun removeNestingGraph(graph: DagreGraph, state: NestingState) {
    graph.removeNode(state.root)
    graph.edges.toList().filter { graph.edge(it.id)?.nestingEdge == true }.forEach { graph.removeEdge(it.id) }
}

private data class Postorder(val low: Int, val limit: Int)

internal fun parentDummyChains(graph: DagreGraph, chains: List<String>) {
    if (!graph.isCompound) return
    val numbers = hashMapOf<String, Postorder>()
    var counter = 0
    fun number(id: String) {
        val low = counter
        graph.children(id).orEmpty().forEach(::number)
        numbers[id] = Postorder(low, counter++)
    }
    graph.children().orEmpty().forEach(::number)
    fun path(source: String, target: String): Pair<List<String?>, String?> {
        val sourceNumber = numbers[source] ?: return emptyList<String?>() to null
        val targetNumber = numbers[target] ?: return emptyList<String?>() to null
        val low = minOf(sourceNumber.low, targetNumber.low)
        val limit = maxOf(sourceNumber.limit, targetNumber.limit)
        val sourcePath = mutableListOf<String?>()
        val targetPath = mutableListOf<String>()
        var parent: String? = source
        do {
            parent = parent?.let(graph::parent)
            sourcePath += parent
        } while (parent != null && ((numbers[parent]?.low ?: low) > low || limit > (numbers[parent]?.limit ?: limit)))
        val lca = parent
        parent = target
        while (parent != null) {
            val next = graph.parent(parent) ?: break
            if (next == lca) break
            parent = next
            targetPath += next
        }
        return sourcePath + targetPath.asReversed() to lca
    }
    chains.forEach { first ->
        val firstNode = graph.node(first) ?: return@forEach
        val target = firstNode.edgeTarget ?: return@forEach
        val (path, lca) = path(firstNode.edgeSource ?: return@forEach, target)
        var pathIndex = 0
        var pathNode = path.getOrNull(pathIndex)
        var ascending = true
        var current = first
        while (current != target) {
            val node = graph.node(current) ?: break
            if (ascending) {
                while (pathIndex < path.size) {
                    val candidate = path[pathIndex]
                    if (candidate == lca) break
                    val maxRank = candidate?.let(graph::node)?.maxRank ?: break
                    if (maxRank >= node.rank) break
                    pathIndex++
                }
                pathNode = path.getOrNull(pathIndex)
                if (pathNode == lca) ascending = false
            }
            if (!ascending) {
                while (pathIndex < path.lastIndex) {
                    val candidate = path[pathIndex + 1]
                    val minRank = candidate?.let(graph::node)?.minRank ?: break
                    if (minRank > node.rank) break
                    pathIndex++
                }
                pathNode = path.getOrNull(pathIndex)
            }
            if (pathNode != null) graph.setParent(current, pathNode)
            current = graph.successors(current)?.firstOrNull() ?: break
        }
    }
}

internal fun addBorderSegments(graph: DagreGraph) {
    if (!graph.isCompound) return
    fun visit(id: String) {
        graph.children(id).orEmpty().toList().forEach(::visit)
        val node = graph.node(id) ?: return
        val minimum = node.minRank ?: return
        val maximum = node.maxRank ?: return
        node.borderLeft.clear()
        node.borderRight.clear()
        for (rank in minimum..maximum) {
            fun add(left: Boolean) {
                val current = addBorderNode(graph, if (left) "_bl" else "_br", rank)
                graph.node(current)!!.borderType = if (left) BorderType.LEFT else BorderType.RIGHT
                val map = if (left) node.borderLeft else node.borderRight
                map[rank] = current
                graph.setParent(current, id)
                map[rank - 1]?.let { graph.setEdge(it, current, DagreEdgeLabel()) }
            }
            add(true)
            add(false)
        }
    }
    graph.children().orEmpty().toList().forEach(::visit)
}
