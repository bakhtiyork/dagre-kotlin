// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre.internal

import io.github.bakhtiyork.dagre.*
import kotlin.math.max

internal fun rank(graph: DagreGraph, algorithm: RankingAlgorithm, custom: ((DagreGraph) -> Unit)?) {
    if (custom != null) return custom(graph)
    when (algorithm) {
        RankingAlgorithm.LONGEST_PATH -> longestPath(graph)
        RankingAlgorithm.TIGHT_TREE -> { longestPath(graph); feasibleTree(graph) }
        RankingAlgorithm.NETWORK_SIMPLEX -> networkSimplex(graph)
        RankingAlgorithm.NONE -> Unit
    }
}

internal fun longestPath(graph: DagreGraph) {
    val visited = hashSetOf<String>()
    fun dfs(id: String): Int {
        val label = graph.node(id) ?: return 0
        if (!visited.add(id)) return label.rank
        var rank = Int.MAX_VALUE
        graph.outEdges(id).orEmpty().forEach { edge ->
            rank = minOf(rank, dfs(edge.target) - (graph.edge(edge.id)?.minLength ?: 1))
        }
        label.rank = if (rank == Int.MAX_VALUE) 0 else rank
        return label.rank
    }
    graph.sources.forEach(::dfs)
}

internal fun slack(graph: DagreGraph, edge: Edge): Int {
    val source = graph.node(edge.source) ?: return 0
    val target = graph.node(edge.target) ?: return 0
    return target.rank - source.rank - (graph.edge(edge.id)?.minLength ?: return 0)
}

private class TreeNode {
    var parent: String? = null
    var low = 0
    var lim = 0
}
private class TreeEdge { var cutValue = 0 }
private typealias Tree = Graph<TreeNode, TreeEdge>

private fun feasibleTree(graph: DagreGraph): Tree {
    val tree = Tree(GraphOptions(directed = false))
    val first = graph.nodes.firstOrNull() ?: return tree
    tree.setNode(first, TreeNode())
    fun tightTree(): Int {
        fun dfs(id: String) {
            graph.nodeEdges(id).orEmpty().forEach { edge ->
                val other = if (id == edge.source) edge.target else edge.source
                if (!tree.hasNode(other) && slack(graph, edge) == 0) {
                    tree.setNode(other, TreeNode())
                    tree.setEdge(id, other, TreeEdge())
                    dfs(other)
                }
            }
        }
        tree.nodes.toList().forEach(::dfs)
        return tree.nodeCount
    }
    while (tightTree() < graph.nodeCount) {
        val edge = graph.edges.filter { tree.hasNode(it.source) != tree.hasNode(it.target) }
            .minByOrNull { slack(graph, it) } ?: break
        val delta = if (tree.hasNode(edge.source)) slack(graph, edge) else -slack(graph, edge)
        tree.nodes.forEach { graph.node(it)!!.rank += delta }
    }
    return tree
}

internal fun networkSimplex(graph: DagreGraph) {
    val simplified = simplify(graph)
    longestPath(simplified)
    val tree = feasibleTree(simplified)
    initLowLim(tree)
    initCutValues(tree, simplified)
    while (true) {
        val leave = tree.edges.firstOrNull { (tree.edge(it.id)?.cutValue ?: 0) < 0 } ?: break
        val enter = enterEdge(tree, simplified, leave) ?: break
        tree.removeEdge(leave.id)
        tree.setEdge(enter.source, enter.target, TreeEdge())
        initLowLim(tree)
        initCutValues(tree, simplified)
        updateRanks(tree, simplified)
    }
    graph.nodes.forEach { id -> simplified.node(id)?.let { graph.node(id)!!.rank = it.rank } }
}

private fun initLowLim(tree: Tree, root: String? = null) {
    val start = root ?: tree.nodes.firstOrNull() ?: return
    val visited = hashSetOf<String>()
    var counter = 1
    fun dfs(id: String, parent: String?): Int {
        val label = tree.node(id) ?: return counter
        val low = counter
        visited += id
        tree.neighbors(id).orEmpty().filterNot { it in visited }.forEach { counter = dfs(it, id) }
        label.low = low
        label.lim = counter++
        label.parent = parent
        return counter
    }
    dfs(start, null)
}

private fun initCutValues(tree: Tree, graph: DagreGraph) {
    val result = mutableListOf<String>()
    val visited = hashSetOf<String>()
    fun dfs(id: String) {
        visited += id
        tree.neighbors(id).orEmpty().filterNot { it in visited }.forEach(::dfs)
        result += id
    }
    tree.nodes.firstOrNull()?.let(::dfs)
    result.dropLast(1).forEach { child ->
        val parent = tree.node(child)?.parent ?: return@forEach
        tree.edge(child, parent)?.cutValue = calculateCutValue(tree, graph, child)
    }
}

private fun calculateCutValue(tree: Tree, graph: DagreGraph, child: String): Int {
    val parent = tree.node(child)?.parent ?: return 0
    var childIsTail = true
    var base = graph.edge(child, parent)
    if (base == null) { childIsTail = false; base = graph.edge(parent, child) }
    var cut = base?.weight ?: return 0
    graph.nodeEdges(child).orEmpty().forEach { edge ->
        val outgoing = edge.source == child
        val other = if (outgoing) edge.target else edge.source
        if (other != parent) {
            val pointsToHead = outgoing == childIsTail
            val weight = graph.edge(edge.id)?.weight ?: 1
            cut += if (pointsToHead) weight else -weight
            tree.edge(child, other)?.cutValue?.let { cut += if (pointsToHead) -it else it }
        }
    }
    return cut
}

private fun enterEdge(tree: Tree, graph: DagreGraph, leaving: Edge): Edge? {
    var source = leaving.source
    var target = leaving.target
    if (!graph.hasEdge(source, target)) source = target.also { target = source }
    val sourceLabel = tree.node(source) ?: return null
    val targetLabel = tree.node(target) ?: return null
    var tail = sourceLabel
    var flip = false
    if (sourceLabel.lim > targetLabel.lim) { tail = targetLabel; flip = true }
    fun descendant(label: TreeNode): Boolean = tail.low <= label.lim && label.lim <= tail.lim
    return graph.edges.filter { edge ->
        val v = tree.node(edge.source) ?: return@filter false
        val w = tree.node(edge.target) ?: return@filter false
        flip == descendant(v) && flip != descendant(w)
    }.minByOrNull { slack(graph, it) }
}

private fun updateRanks(tree: Tree, graph: DagreGraph) {
    val root = tree.nodes.firstOrNull { tree.node(it)?.parent == null } ?: return
    val order = mutableListOf<String>()
    val visited = hashSetOf<String>()
    fun dfs(id: String) {
        visited += id; order += id
        tree.neighbors(id).orEmpty().filterNot { it in visited }.forEach(::dfs)
    }
    dfs(root)
    order.drop(1).forEach { id ->
        val parent = tree.node(id)?.parent ?: return@forEach
        val forward = graph.edge(id, parent)
        val edge = forward ?: graph.edge(parent, id) ?: return@forEach
        graph.node(id)!!.rank = graph.node(parent)!!.rank + if (forward == null) edge.minLength else -edge.minLength
    }
}
