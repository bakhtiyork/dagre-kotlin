// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre

/**
 * Deterministic directed/undirected, simple/multi, optionally compound graph.
 * Labels are deliberately stored by reference so layout results update callers in place.
 */
class Graph<NodeLabel : Any, EdgeLabel : Any>(
    val options: GraphOptions = GraphOptions(),
) {
    private val nodeLabels = LinkedHashMap<String, NodeLabel>()
    private val nodeIds = LinkedHashSet<String>()
    private val edgeLabels = LinkedHashMap<EdgeId, EdgeLabel>()
    private val incoming = LinkedHashMap<String, MutableList<EdgeId>>()
    private val outgoing = LinkedHashMap<String, MutableList<EdgeId>>()
    private val parents = HashMap<String, String?>()
    private val childIds = HashMap<String?, LinkedHashSet<String>>().apply { put(null, linkedSetOf()) }
    private var idCounter = 0L

    private var defaultNodeLabel: ((String) -> NodeLabel)? = null
    private var defaultEdgeLabel: ((String, String, String?) -> EdgeLabel)? = null

    val isDirected: Boolean get() = options.directed
    val isMultigraph: Boolean get() = options.multigraph
    val isCompound: Boolean get() = options.compound
    val nodeCount: Int get() = nodeIds.size
    val edgeCount: Int get() = edgeLabels.size
    val nodes: List<String> get() = nodeIds.toList()
    val edges: List<Edge> get() = edgeLabels.keys.map { Edge(it.source, it.target, it.name) }
    val sources: List<String> get() = nodeIds.filter { incoming[it].isNullOrEmpty() }
    val sinks: List<String> get() = nodeIds.filter { outgoing[it].isNullOrEmpty() }

    fun setDefaultNodeLabel(factory: (String) -> NodeLabel) { defaultNodeLabel = factory }
    fun setDefaultEdgeLabel(factory: (String, String, String?) -> EdgeLabel) { defaultEdgeLabel = factory }

    fun setNode(id: String, label: NodeLabel? = null) {
        if (id in nodeIds) {
            if (label != null) nodeLabels[id] = label
            return
        }
        nodeIds += id
        val actual = label ?: defaultNodeLabel?.invoke(id)
        if (actual != null) nodeLabels[id] = actual
        incoming[id] = mutableListOf()
        outgoing[id] = mutableListOf()
        if (isCompound) {
            parents[id] = null
            childIds[id] = linkedSetOf()
            childIds.getValue(null) += id
        }
    }

    fun setNodes(ids: Iterable<String>, label: NodeLabel? = null) = ids.forEach { setNode(it, label) }
    fun node(id: String): NodeLabel? = nodeLabels[id]
    operator fun get(id: String): NodeLabel? = node(id)
    fun hasNode(id: String): Boolean = id in nodeIds

    fun removeNode(id: String) {
        if (id !in nodeIds) return
        (incoming[id].orEmpty() + outgoing[id].orEmpty()).distinct().forEach(::removeEdgeById)
        if (isCompound) {
            childIds[parents[id]]?.remove(id)
            childIds[id].orEmpty().toList().forEach { setParent(it, null) }
            childIds.remove(id)
            parents.remove(id)
        }
        nodeIds.remove(id)
        nodeLabels.remove(id)
        incoming.remove(id)
        outgoing.remove(id)
    }

    fun setEdge(source: String, target: String, label: EdgeLabel? = null, name: String? = null) {
        if (!isMultigraph && name != null) throw GraphException.NamedEdgeRequiresMultigraph()
        if (!hasNode(source)) setNode(source)
        if (!hasNode(target)) setNode(target)
        val id = normalizedEdgeId(source, target, name)
        if (id in edgeLabels) {
            if (label != null) edgeLabels[id] = label
            return
        }
        val actual = label ?: defaultEdgeLabel?.invoke(source, target, name)
        if (actual == null) return
        edgeLabels[id] = actual
        outgoing.getValue(id.source) += id
        incoming.getValue(id.target) += id
    }

    fun setEdge(edge: EdgeId, label: EdgeLabel? = null) = setEdge(edge.source, edge.target, label, edge.name)
    fun edge(source: String, target: String, name: String? = null): EdgeLabel? =
        edgeLabels[normalizedEdgeId(source, target, name)]
    fun edge(id: EdgeId): EdgeLabel? = edge(id.source, id.target, id.name)
    operator fun get(id: EdgeId): EdgeLabel? = edge(id)
    fun hasEdge(source: String, target: String, name: String? = null): Boolean = edge(source, target, name) != null
    fun hasEdge(id: EdgeId): Boolean = edge(id) != null
    fun removeEdge(source: String, target: String, name: String? = null) =
        removeEdgeById(normalizedEdgeId(source, target, name))
    fun removeEdge(id: EdgeId) = removeEdge(id.source, id.target, id.name)

    private fun removeEdgeById(id: EdgeId) {
        if (edgeLabels.remove(id) == null) return
        outgoing[id.source]?.remove(id)
        incoming[id.target]?.remove(id)
    }

    fun inEdges(id: String, source: String? = null): List<Edge>? = incoming[id]?.asSequence()
        ?.filter { source == null || it.source == source }
        ?.map { Edge(it.source, it.target, it.name) }?.toList()
    fun outEdges(id: String, target: String? = null): List<Edge>? = outgoing[id]?.asSequence()
        ?.filter { target == null || it.target == target }
        ?.map { Edge(it.source, it.target, it.name) }?.toList()
    fun nodeEdges(id: String, other: String? = null): List<Edge>? {
        if (!hasNode(id)) return null
        return (inEdges(id, other).orEmpty() + outEdges(id, other).orEmpty()).distinct()
    }

    fun predecessors(id: String): List<String>? = incoming[id]?.mapTo(LinkedHashSet()) { it.source }?.toList()
    fun successors(id: String): List<String>? = outgoing[id]?.mapTo(LinkedHashSet()) { it.target }?.toList()
    fun neighbors(id: String): List<String>? {
        if (!hasNode(id)) return null
        return LinkedHashSet<String>().apply {
            addAll(predecessors(id).orEmpty())
            addAll(successors(id).orEmpty())
        }.toList()
    }

    fun isLeaf(id: String): Boolean = !isCompound || childIds[id].isNullOrEmpty()

    fun setParent(id: String, parent: String?) {
        if (!isCompound) throw GraphException.ParentRequiresCompoundGraph()
        if (!hasNode(id)) setNode(id)
        if (parent != null && !hasNode(parent)) setNode(parent)
        var ancestor = parent
        while (ancestor != null) {
            if (ancestor == id) throw GraphException.ParentCycle(requireNotNull(parent), id)
            ancestor = parents[ancestor]
        }
        childIds[parents[id]]?.remove(id)
        parents[id] = parent
        childIds.getOrPut(parent) { linkedSetOf() } += id
    }

    fun parent(id: String): String? = if (isCompound) parents[id] else null
    fun children(id: String? = null): List<String>? {
        if (!isCompound) return if (id == null) nodes else if (hasNode(id)) emptyList() else null
        if (id != null && !hasNode(id)) return null
        val children = childIds[id].orEmpty()
        return nodeIds.filter { it in children }
    }

    fun descendants(id: String): Set<String> {
        if (!isCompound) return emptySet()
        val result = LinkedHashSet<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        while (stack.isNotEmpty()) children(stack.removeLast()).orEmpty().forEach {
            if (result.add(it)) stack.add(it)
        }
        return result
    }

    fun ancestors(id: String): List<String> = buildList {
        if (!isCompound) return@buildList
        var current = parent(id)
        while (current != null) {
            add(current)
            current = parent(current)
        }
    }

    fun lowestCommonAncestor(first: String, second: String): String? {
        if (!isCompound) return null
        val firstAncestors = (listOf(first) + ancestors(first)).toSet()
        var current: String? = second
        while (current != null) {
            if (current in firstAncestors) return current
            current = parent(current)
        }
        return null
    }

    fun filterEdges(predicate: (Edge) -> Boolean): List<Edge> = edges.filter(predicate)

    fun filterNodes(predicate: (String) -> Boolean): Graph<NodeLabel, EdgeLabel> {
        val result = Graph<NodeLabel, EdgeLabel>(options)
        nodes.filter(predicate).forEach { result.setNode(it, node(it)) }
        edges.filter { result.hasNode(it.source) && result.hasNode(it.target) }
            .forEach { result.setEdge(it.source, it.target, edge(it.id), it.name) }
        if (isCompound) result.nodes.forEach { id ->
            parent(id)?.takeIf(result::hasNode)?.let { result.setParent(id, it) }
        }
        return result
    }

    fun copy(): Graph<NodeLabel, EdgeLabel> = filterNodes { true }

    fun setPath(ids: List<String>, label: EdgeLabel? = null) {
        ids.zipWithNext().forEach { (source, target) -> setEdge(source, target, label) }
    }

    internal fun uniqueId(prefix: String): String {
        while (true) {
            val candidate = "$prefix${++idCounter}"
            if (!hasNode(candidate) && edges.none { it.name == candidate }) return candidate
        }
    }

    private fun normalizedEdgeId(source: String, target: String, name: String?): EdgeId {
        val edgeName = if (isMultigraph) name else null
        return if (isDirected || source <= target) EdgeId(source, target, edgeName)
        else EdgeId(target, source, edgeName)
    }
}
