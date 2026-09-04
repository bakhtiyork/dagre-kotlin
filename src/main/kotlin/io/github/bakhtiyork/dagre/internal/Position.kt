// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre.internal

import io.github.bakhtiyork.dagre.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private class Conflicts {
    private val values = hashMapOf<String, MutableSet<String>>()
    fun add(first: String, second: String) {
        val (small, large) = if (first < second) first to second else second to first
        values.getOrPut(small) { hashSetOf() } += large
    }
    fun has(first: String, second: String): Boolean {
        val (small, large) = if (first < second) first to second else second to first
        return large in values[small].orEmpty()
    }
}
private data class VerticalAlignment(val root: MutableMap<String, String>, val align: MutableMap<String, String>)

internal fun position(graph: DagreGraph, nodeSeparation: Double, edgeSeparation: Double, rankSeparation: Double, alignment: Alignment?) {
    val simple = asNonCompoundGraph(graph)
    var previousY = 0.0
    buildLayerMatrix(simple).forEach { layer ->
        val height = layer.maxOfOrNull { simple.node(it)?.height ?: 0.0 } ?: 0.0
        layer.forEach { simple.node(it)?.y = previousY + height / 2 }
        previousY += height + rankSeparation
    }
    positionX(simple, nodeSeparation, edgeSeparation, alignment).forEach { (id, x) -> graph.node(id)?.x = x }
    simple.nodes.forEach { id -> graph.node(id)?.y = simple.node(id)?.y ?: 0.0 }
}

internal fun positionX(graph: DagreGraph, nodeSeparation: Double, edgeSeparation: Double, requested: Alignment?): Map<String, Double> {
    val layering = buildLayerMatrix(graph)
    val conflicts = Conflicts()
    findType1Conflicts(graph, layering, conflicts)
    findType2Conflicts(graph, layering, conflicts)
    val all = linkedMapOf<String, MutableMap<String, Double>>()
    listOf("u", "d").forEach { vertical ->
        var adjusted = if (vertical == "u") layering else layering.asReversed()
        listOf("l", "r").forEach { horizontal ->
            if (horizontal == "r") adjusted = adjusted.map { it.asReversed() }
            val neighbors: (String) -> List<String> = if (vertical == "u") {
                { graph.predecessors(it).orEmpty() }
            } else {
                { graph.successors(it).orEmpty() }
            }
            val alignment = verticalAlignment(graph, adjusted, conflicts, neighbors)
            val positions = horizontalCompaction(
                graph, adjusted, alignment.root, alignment.align,
                horizontal == "r", nodeSeparation, edgeSeparation,
            )
            if (horizontal == "r") positions.replaceAll { _, x -> -x }
            all[vertical + horizontal] = positions
            if (horizontal == "r") adjusted = adjusted.map { it.asReversed() }
        }
    }
    val narrowest = all.values.minByOrNull { xs ->
        val minimum = xs.minOfOrNull { (id, x) -> x - (graph.node(id)?.width ?: 0.0) / 2 } ?: 0.0
        val maximum = xs.maxOfOrNull { (id, x) -> x + (graph.node(id)?.width ?: 0.0) / 2 } ?: 0.0
        maximum - minimum
    }.orEmpty()
    if (narrowest.isNotEmpty()) {
        val targetMin = narrowest.values.minOrNull() ?: 0.0
        val targetMax = narrowest.values.maxOrNull() ?: 0.0
        all.forEach { (key, xs) ->
            if (xs != narrowest) {
                val delta = if (key.endsWith("l")) targetMin - (xs.values.minOrNull() ?: 0.0)
                else targetMax - (xs.values.maxOrNull() ?: 0.0)
                if (delta != 0.0) xs.replaceAll { _, x -> x + delta }
            }
        }
    }
    val preferred = when (requested) {
        Alignment.UP_LEFT -> "ul"
        Alignment.UP_RIGHT -> "ur"
        Alignment.DOWN_LEFT -> "dl"
        Alignment.DOWN_RIGHT -> "dr"
        null -> null
    }
    val result = linkedMapOf<String, Double>()
    all["ul"].orEmpty().keys.forEach { id ->
        if (preferred != null) result[id] = all[preferred]?.get(id) ?: 0.0
        else {
            val values = all.values.mapNotNull { it[id] }.sorted()
            if (values.isNotEmpty()) result[id] = if (values.size >= 4) (values[1] + values[2]) / 2 else values[values.size / 2]
        }
    }
    return result
}

private fun findType1Conflicts(graph: DagreGraph, layering: List<List<String>>, conflicts: Conflicts) {
    for (rank in 1 until layering.size) {
        val north = layering[rank - 1]
        val south = layering[rank]
        var previous = 0
        var scan = 0
        south.forEachIndexed { index, id ->
            val other = if (graph.node(id)?.dummy != null) graph.predecessors(id)?.firstOrNull { graph.node(it)?.dummy != null } else null
            val next = other?.let { graph.node(it)?.order } ?: north.size
            if (other != null || index == south.lastIndex) {
                for (position in scan..index) {
                    val current = south[position]
                    graph.predecessors(current).orEmpty().forEach { predecessor ->
                        val predecessorPosition = graph.node(predecessor)?.order ?: 0
                        if ((predecessorPosition < previous || next < predecessorPosition) &&
                            !(graph.node(predecessor)?.dummy != null && graph.node(current)?.dummy != null)
                        ) conflicts.add(predecessor, current)
                    }
                }
                scan = index + 1
                previous = next
            }
        }
    }
}

private fun findType2Conflicts(graph: DagreGraph, layering: List<List<String>>, conflicts: Conflicts) {
    fun scan(south: List<String>, start: Int, end: Int, previous: Int, next: Int) {
        for (index in start until end) {
            val id = south[index]
            if (graph.node(id)?.dummy != null) graph.predecessors(id).orEmpty().forEach { predecessor ->
                val node = graph.node(predecessor)
                if (node?.dummy != null && (node.order < previous || node.order > next)) conflicts.add(predecessor, id)
            }
        }
    }
    for (rank in 1 until layering.size) {
        val north = layering[rank - 1]
        val south = layering[rank]
        var previousNorth = -1
        var nextNorth = 0
        var southPosition = 0
        south.forEachIndexed { lookahead, id ->
            if (graph.node(id)?.dummy == DummyType.BORDER) {
                graph.predecessors(id)?.firstOrNull()?.let { predecessor ->
                    nextNorth = graph.node(predecessor)?.order ?: 0
                    scan(south, southPosition, lookahead, previousNorth, nextNorth)
                    southPosition = lookahead
                    previousNorth = nextNorth
                }
            }
            scan(south, southPosition, south.size, nextNorth, north.size)
        }
    }
}

private fun verticalAlignment(
    graph: DagreGraph,
    layering: List<List<String>>,
    conflicts: Conflicts,
    neighborsOf: (String) -> List<String>,
): VerticalAlignment {
    val roots = linkedMapOf<String, String>()
    val alignments = linkedMapOf<String, String>()
    val positions = hashMapOf<String, Int>()
    layering.forEach { layer -> layer.forEachIndexed { index, id -> roots[id] = id; alignments[id] = id; positions[id] = index } }
    layering.forEach { layer ->
        var previous = -1
        layer.forEach { id ->
            val neighbors = neighborsOf(id).sortedBy { positions[it] ?: 0 }
            if (neighbors.isNotEmpty()) {
                val middle = (neighbors.size - 1) / 2.0
                for (index in floor(middle).toInt()..ceil(middle).toInt()) {
                    val neighbor = neighbors[index]
                    val position = positions[neighbor] ?: 0
                    if (alignments[id] == id && previous < position && !conflicts.has(id, neighbor)) {
                        roots[neighbor]?.let { neighborRoot ->
                            alignments[neighbor] = id
                            alignments[id] = neighborRoot
                            roots[id] = neighborRoot
                            previous = position
                        }
                    }
                }
            }
        }
    }
    return VerticalAlignment(roots, alignments)
}

private fun horizontalCompaction(
    graph: DagreGraph,
    layering: List<List<String>>,
    roots: Map<String, String>,
    alignments: Map<String, String>,
    reverseSeparation: Boolean,
    nodeSeparation: Double,
    edgeSeparation: Double,
): MutableMap<String, Double> {
    val blocks = Graph<Unit, Double>()
    layering.forEach { layer ->
        var previous: String? = null
        layer.forEach { id ->
            val root = roots[id] ?: return@forEach
            blocks.setNode(root, Unit)
            val old = previous
            if (old != null) roots[old]?.let { oldRoot ->
                val separation = separation(graph, id, old, reverseSeparation, nodeSeparation, edgeSeparation)
                blocks.setEdge(oldRoot, root, max(separation, blocks.edge(oldRoot, root) ?: 0.0))
            }
            previous = id
        }
    }
    val xs = linkedMapOf<String, Double>()
    val visited = hashSetOf<String>()
    val stack = ArrayDeque<String>().apply { addAll(blocks.nodes) }
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (id in visited) {
            xs[id] = blocks.inEdges(id).orEmpty().maxOfOrNull { edge -> (xs[edge.source] ?: 0.0) + (blocks.edge(edge.id) ?: 0.0) } ?: 0.0
        } else {
            visited += id; stack += id; blocks.predecessors(id).orEmpty().forEach(stack::add)
        }
    }
    visited.clear()
    stack.addAll(blocks.nodes)
    val excludedBorder = if (reverseSeparation) BorderType.LEFT else BorderType.RIGHT
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (id in visited) {
            val minimum = blocks.outEdges(id).orEmpty().minOfOrNull { edge ->
                (xs[edge.target] ?: Double.POSITIVE_INFINITY) - (blocks.edge(edge.id) ?: 0.0)
            }
            if (minimum != null && minimum.isFinite() && graph.node(id)?.borderType != excludedBorder) xs[id] = max(xs[id] ?: 0.0, minimum)
        } else {
            visited += id; stack += id; blocks.successors(id).orEmpty().forEach(stack::add)
        }
    }
    alignments.keys.forEach { id -> roots[id]?.let { xs[id] = xs[it] ?: 0.0 } }
    return xs
}

private fun separation(
    graph: DagreGraph,
    current: String,
    previous: String,
    reverse: Boolean,
    nodeSeparation: Double,
    edgeSeparation: Double,
): Double {
    val currentLabel = graph.node(current) ?: return 0.0
    val previousLabel = graph.node(previous) ?: return 0.0
    var result = currentLabel.width / 2
    var delta = when (currentLabel.labelPosition) {
        LabelPosition.LEFT -> -currentLabel.width / 2
        LabelPosition.RIGHT -> currentLabel.width / 2
        else -> 0.0
    }
    if (delta != 0.0) result += if (reverse) delta else -delta
    result += (if (currentLabel.dummy != null) edgeSeparation else nodeSeparation) / 2
    result += (if (previousLabel.dummy != null) edgeSeparation else nodeSeparation) / 2
    result += previousLabel.width / 2
    delta = when (previousLabel.labelPosition) {
        LabelPosition.LEFT -> previousLabel.width / 2
        LabelPosition.RIGHT -> -previousLabel.width / 2
        else -> 0.0
    }
    if (delta != 0.0) result += if (reverse) delta else -delta
    return result
}
