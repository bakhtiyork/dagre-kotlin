// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre.internal

import io.github.bakhtiyork.dagre.*

internal fun initialOrder(graph: DagreGraph): List<List<String>> {
    val simple = graph.nodes.filter(graph::isLeaf)
    val max = simple.maxOfOrNull { graph.node(it)?.rank ?: 0 } ?: return emptyList()
    val layers = MutableList(max + 1) { mutableListOf<String>() }
    val visited = hashSetOf<String>()
    fun dfs(id: String) {
        if (!visited.add(id)) return
        graph.node(id)?.rank?.takeIf { it in layers.indices }?.let { layers[it] += id }
        graph.successors(id).orEmpty().forEach(::dfs)
    }
    simple.sortedBy { graph.node(it)?.rank ?: 0 }.forEach(::dfs)
    return layers
}

internal fun crossCount(graph: DagreGraph, layering: List<List<String>>): Int {
    var result = 0
    for (layer in 1 until layering.size) result += twoLayerCrossCount(graph, layering[layer - 1], layering[layer])
    return result
}

private fun twoLayerCrossCount(graph: DagreGraph, north: List<String>, south: List<String>): Int {
    if (south.isEmpty()) return 0
    val positions = south.withIndex().associate { it.value to it.index }
    val entries = buildList {
        north.forEach { id ->
            graph.outEdges(id).orEmpty().mapNotNull { edge ->
                positions[edge.target]?.let { it to (graph.edge(edge.id)?.weight ?: 1) }
            }.sortedBy { it.first }.forEach(::add)
        }
    }
    if (entries.isEmpty()) return 0
    var first = 1
    while (first < south.size) first = first shl 1
    val tree = IntArray(2 * first - 1)
    first--
    var crossings = 0
    entries.forEach { (position, weight) ->
        var index = position + first
        tree[index] += weight
        var sum = 0
        while (index > 0) {
            if (index % 2 == 1) sum += tree[index + 1]
            index = (index - 1) shr 1
            tree[index] += weight
        }
        crossings += weight * sum
    }
    return crossings
}

private class LayerNode {
    var order = 0
    var borderLeft: String? = null
    var borderRight: String? = null
}
private data class LayerEdge(var weight: Int)
private typealias LayerGraph = Graph<LayerNode, LayerEdge>
private data class LayerGraphAndRoot(val graph: LayerGraph, val root: String)

private fun buildLayerGraph(main: DagreGraph, rank: Int, incoming: Boolean, nodes: List<String>): LayerGraphAndRoot {
    var index = 0
    var root = "_root$index"
    while (main.hasNode(root)) root = "_root${++index}"
    val graph = LayerGraph(GraphOptions(multigraph = false, compound = true))
    graph.setDefaultNodeLabel { id -> LayerNode().also { it.order = main.node(id)?.order ?: 0 } }
    nodes.forEach { id ->
        val node = main.node(id) ?: return@forEach
        val belongs = if (node.minRank != null && node.maxRank != null) rank in node.minRank!!..node.maxRank!! else node.rank == rank
        if (!belongs) return@forEach
        val label = LayerNode().also { it.order = node.order }
        graph.setNode(id, label)
        if (!graph.hasNode(root)) graph.setNode(root, LayerNode())
        graph.setParent(id, main.parent(id) ?: root)
        val edges = if (incoming) main.inEdges(id) else main.outEdges(id)
        edges.orEmpty().forEach { edge ->
            val other = if (edge.source == id) edge.target else edge.source
            val weight = main.edge(edge.id)?.weight ?: 1
            graph.setEdge(other, id, LayerEdge((graph.edge(other, id)?.weight ?: 0) + weight))
        }
        if (node.minRank != null) {
            label.borderLeft = node.borderLeft[rank]
            label.borderRight = node.borderRight[rank]
        }
    }
    if (!graph.hasNode(root)) graph.setNode(root, LayerNode())
    return LayerGraphAndRoot(graph, root)
}

private data class BaryEntry(
    val node: String,
    var barycenter: Double? = null,
    var weight: Int = 0,
    var index: Int = 0,
)
private data class SortResult(var nodes: List<String>, var barycenter: Double? = null, var weight: Int = 0)
private class MappedEntry(entry: BaryEntry, index: Int) {
    var indegree = 0
    val incoming = mutableListOf<MappedEntry>()
    val outgoing = mutableListOf<MappedEntry>()
    var nodes = listOf(entry.node)
    var index = index
    var barycenter = entry.barycenter
    var weight = entry.weight
    var merged = false
}
private data class ResolvedEntry(val nodes: List<String>, val index: Int, val barycenter: Double?, val weight: Int)

private fun sortSubgraph(graph: LayerGraph, node: String, constraints: Graph<Unit, Unit>, biasRight: Boolean): SortResult {
    var movable = graph.children(node).orEmpty()
    val left = graph.node(node)?.borderLeft
    val right = graph.node(node)?.borderRight
    if (left != null && right != null) movable = movable.filter { it != left && it != right }
    val entries = movable.mapIndexed { index, id ->
        val edges = graph.inEdges(id).orEmpty()
        var sum = 0.0
        var weight = 0
        edges.forEach { edge ->
            val edgeWeight = graph.edge(edge.id)?.weight ?: 1
            sum += edgeWeight * (graph.node(edge.source)?.order ?: 0).toDouble()
            weight += edgeWeight
        }
        BaryEntry(id, if (weight > 0) sum / weight else null, weight, index)
    }.toMutableList()
    val subgraphs = hashMapOf<String, SortResult>()
    entries.forEach { entry ->
        if (!graph.children(entry.node).isNullOrEmpty()) {
            val subgraph = sortSubgraph(graph, entry.node, constraints, biasRight)
            subgraphs[entry.node] = subgraph
            val other = subgraph.barycenter
            if (other != null) {
                if (entry.barycenter != null) {
                    val total = entry.weight + subgraph.weight
                    entry.barycenter = (entry.barycenter!! * entry.weight + other * subgraph.weight) / total
                    entry.weight = total
                } else { entry.barycenter = other; entry.weight = subgraph.weight }
            }
        }
    }
    val resolved = resolveConflicts(entries, constraints)
    val sorted = sortEntries(resolved, biasRight).flatMap { subgraphs[it]?.nodes ?: listOf(it) }
    val result = SortResult(sorted)
    if (left != null && right != null) {
        result.nodes = listOf(left) + result.nodes + right
        val leftOrder = graph.predecessors(left)?.firstOrNull()?.let { graph.node(it)?.order }
        val rightOrder = graph.predecessors(right)?.firstOrNull()?.let { graph.node(it)?.order }
        if (leftOrder != null && rightOrder != null) {
            val bc = result.barycenter ?: 0.0
            result.barycenter = (bc * result.weight + leftOrder + rightOrder) / (result.weight + 2)
            result.weight += 2
        }
    }
    if (result.barycenter == null) {
        var sum = 0.0
        var weight = 0
        resolved.forEach { if (it.barycenter != null) { sum += it.barycenter * it.weight; weight += it.weight } }
        if (weight > 0) { result.barycenter = sum / weight; result.weight = weight }
    }
    return result
}

private fun resolveConflicts(entries: List<BaryEntry>, constraints: Graph<Unit, Unit>): List<ResolvedEntry> {
    val mapped = linkedMapOf<String, MappedEntry>()
    entries.forEachIndexed { index, entry -> mapped[entry.node] = MappedEntry(entry, index) }
    constraints.edges.forEach { edge ->
        val source = mapped[edge.source] ?: return@forEach
        val target = mapped[edge.target] ?: return@forEach
        target.indegree++
        source.outgoing += target
    }
    val sources = mapped.values.filterTo(mutableListOf()) { it.indegree == 0 }
    val result = mutableListOf<MappedEntry>()
    while (sources.isNotEmpty()) {
        val entry = sources.removeLast()
        result += entry
        entry.incoming.asReversed().forEach { source ->
            if (!source.merged && (source.barycenter == null || entry.barycenter == null || source.barycenter!! >= entry.barycenter!!)) {
                var sum = 0.0
                var weight = 0
                if (entry.weight > 0 && entry.barycenter != null) { sum += entry.barycenter!! * entry.weight; weight += entry.weight }
                if (source.weight > 0 && source.barycenter != null) { sum += source.barycenter!! * source.weight; weight += source.weight }
                entry.nodes = source.nodes + entry.nodes
                entry.barycenter = if (weight > 0) sum / weight else null
                entry.weight = weight
                entry.index = minOf(source.index, entry.index)
                source.merged = true
            }
        }
        entry.outgoing.forEach { target ->
            target.incoming += entry
            if (--target.indegree == 0) sources += target
        }
    }
    return result.filterNot { it.merged }.map { ResolvedEntry(it.nodes, it.index, it.barycenter, it.weight) }
}

private fun sortEntries(entries: List<ResolvedEntry>, biasRight: Boolean): List<String> {
    val sortable = entries.filter { it.barycenter != null }.sortedWith { a, b ->
        val byBarycenter = a.barycenter!!.compareTo(b.barycenter!!)
        if (byBarycenter != 0) byBarycenter else if (biasRight) b.index - a.index else a.index - b.index
    }
    val unsortable = entries.filter { it.barycenter == null }.sortedByDescending { it.index }.toMutableList()
    val groups = mutableListOf<List<String>>()
    var index = 0
    fun consume() { while (unsortable.lastOrNull()?.index?.let { it <= index } == true) { groups += unsortable.removeLast().nodes; index++ } }
    consume()
    sortable.forEach { entry -> index += entry.nodes.size; groups += entry.nodes; consume() }
    return groups.flatten()
}

private fun addSubgraphConstraints(graph: LayerGraph, constraints: Graph<Unit, Unit>, nodes: List<String>) {
    val previous = hashMapOf<String, String>()
    var rootPrevious: String? = null
    nodes.forEach { id ->
        var child = graph.parent(id)
        while (child != null) {
            val parent = graph.parent(child)
            val old = if (parent != null) previous.put(parent, child) else rootPrevious.also { rootPrevious = child }
            if (old != null && old != child) {
                constraints.setEdge(old, child, Unit)
                break
            }
            child = parent
        }
    }
}

internal fun order(graph: DagreGraph, custom: ((DagreGraph, List<List<String>>) -> Unit)? = null) {
    if (custom != null) return custom(graph, initialOrder(graph))
    val maximum = maxRank(graph)
    val byRank = hashMapOf<Int, MutableList<String>>()
    graph.nodes.forEach { id ->
        val node = graph.node(id) ?: return@forEach
        byRank.getOrPut(node.rank) { mutableListOf() } += id
        if (node.minRank != null && node.maxRank != null) for (rank in node.minRank!!..node.maxRank!!) {
            if (rank != node.rank) byRank.getOrPut(rank) { mutableListOf() } += id
        }
    }
    val down = if (maximum >= 1) (1..maximum).map { buildLayerGraph(graph, it, true, byRank[it].orEmpty()) } else emptyList()
    val up = if (maximum >= 1) (maximum - 1 downTo 0).map { buildLayerGraph(graph, it, false, byRank[it].orEmpty()) } else emptyList()
    fun assign(layering: List<List<String>>) = layering.forEach { layer -> layer.forEachIndexed { index, id -> graph.node(id)?.order = index } }
    var layering = initialOrder(graph)
    assign(layering)
    var bestCount = Int.MAX_VALUE
    var best = layering
    var noImprovement = 0
    var iteration = 0
    while (noImprovement < 4) {
        val constraints = Graph<Unit, Unit>()
        val layers = if (iteration % 2 == 1) down else up
        layers.forEach { item ->
            item.graph.nodes.forEach { id -> graph.node(id)?.let { item.graph.node(id)?.order = it.order } }
            val sorted = sortSubgraph(item.graph, item.root, constraints, iteration % 4 >= 2)
            sorted.nodes.forEachIndexed { index, id -> item.graph.node(id)?.order = index; graph.node(id)?.order = index }
            addSubgraphConstraints(item.graph, constraints, sorted.nodes)
        }
        layering = buildLayerMatrix(graph)
        val count = crossCount(graph, layering)
        if (count < bestCount) { bestCount = count; best = layering; noImprovement = 0 } else noImprovement++
        iteration++
    }
    assign(best)
}
