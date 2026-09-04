// SPDX-License-Identifier: MIT
package io.github.bakhtiyork.dagre

/** Options controlling the graph data structure. */
data class GraphOptions(
    val directed: Boolean = true,
    val multigraph: Boolean = false,
    val compound: Boolean = false,
)

/** Stable identity of an edge. Names distinguish parallel edges in a multigraph. */
data class EdgeId(
    val source: String,
    val target: String,
    val name: String? = null,
) {
    fun reversed(): EdgeId = EdgeId(target, source, name)
}

data class Edge(
    val source: String,
    val target: String,
    val name: String? = null,
) {
    val id: EdgeId get() = EdgeId(source, target, name)
}

data class Point(val x: Double, val y: Double)

enum class LabelPosition { LEFT, CENTER, RIGHT }
enum class RankDirection { TOP_TO_BOTTOM, BOTTOM_TO_TOP, LEFT_TO_RIGHT, RIGHT_TO_LEFT }
enum class Alignment { UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT }
enum class RankingAlgorithm { NETWORK_SIMPLEX, TIGHT_TREE, LONGEST_PATH, NONE }
enum class AcyclicAlgorithm { DFS, GREEDY }

/** Mutable input/output data attached to a node. */
class DagreNodeLabel(
    var width: Double = 0.0,
    var height: Double = 0.0,
) {
    var x: Double = 0.0
    var y: Double = 0.0
    var rank: Int = 0
    var order: Int = 0

    internal var dummy: DummyType? = null
    internal var edgeSource: String? = null
    internal var edgeTarget: String? = null
    internal var edgeName: String? = null
    internal var labelPosition: LabelPosition? = null
    internal var borderType: BorderType? = null
    internal var minRank: Int? = null
    internal var maxRank: Int? = null
    internal val borderLeft = mutableMapOf<Int, String>()
    internal val borderRight = mutableMapOf<Int, String>()
    internal var borderTop: String? = null
    internal var borderBottom: String? = null
    internal var paddingLeft = 0.0
    internal var paddingRight = 0.0
    internal var paddingTop = 0.0
    internal var paddingBottom = 0.0
    internal var low = 0
    internal var lim = 0
    internal var edgeRef: EdgeId? = null
    internal var edgeLabel: DagreEdgeLabel? = null
    internal var edgeObject: Edge? = null

    internal fun layoutCopy(): DagreNodeLabel = DagreNodeLabel(width, height).also {
        it.x = x
        it.y = y
        it.rank = rank
        it.order = order
        it.dummy = dummy
        it.edgeSource = edgeSource
        it.edgeTarget = edgeTarget
        it.edgeName = edgeName
        it.labelPosition = labelPosition
        it.borderType = borderType
        it.minRank = minRank
        it.maxRank = maxRank
        it.borderLeft.putAll(borderLeft)
        it.borderRight.putAll(borderRight)
        it.borderTop = borderTop
        it.borderBottom = borderBottom
    }
}

internal enum class DummyType { EDGE, EDGE_LABEL, EDGE_PROXY, BORDER, ROOT, SELF_EDGE }
internal enum class BorderType { TOP, BOTTOM, LEFT, RIGHT }

/** Mutable input/output data attached to an edge. */
class DagreEdgeLabel(
    var minLength: Int = 1,
    var weight: Int = 1,
    var width: Double = 0.0,
    var height: Double = 0.0,
    var labelPosition: LabelPosition = LabelPosition.CENTER,
    var labelOffset: Double = 10.0,
) {
    var points: List<Point> = emptyList()
    var x: Double = 0.0
    var y: Double = 0.0

    internal var hasLabelPosition = false
    internal var reversed = false
    internal var forwardName: String? = null
    internal var labelRank: Int? = null
    internal var nestingEdge = false

    internal fun layoutCopy(): DagreEdgeLabel = DagreEdgeLabel(
        minLength, weight, width, height, labelPosition, labelOffset,
    ).also {
        it.points = points.toList()
        it.x = x
        it.y = y
        it.hasLabelPosition = hasLabelPosition
        it.reversed = reversed
        it.forwardName = forwardName
        it.labelRank = labelRank
        it.nestingEdge = nestingEdge
    }
}

/** Immutable input configuration for one layout invocation. */
class LayoutOptions(
    val rankDirection: RankDirection = RankDirection.TOP_TO_BOTTOM,
    val alignment: Alignment? = null,
    val nodeSeparation: Double = 50.0,
    val edgeSeparation: Double = 20.0,
    val rankSeparation: Double = 50.0,
    val marginX: Double = 0.0,
    val marginY: Double = 0.0,
    val rankingAlgorithm: RankingAlgorithm = RankingAlgorithm.NETWORK_SIMPLEX,
    val acyclicAlgorithm: AcyclicAlgorithm = AcyclicAlgorithm.GREEDY,
    val customRanker: ((DagreGraph) -> Unit)? = null,
    val customOrder: ((DagreGraph, List<List<String>>) -> Unit)? = null,
) {
    init {
        require(nodeSeparation.isFinite() && nodeSeparation >= 0) { "nodeSeparation must be finite and non-negative" }
        require(edgeSeparation.isFinite() && edgeSeparation >= 0) { "edgeSeparation must be finite and non-negative" }
        require(rankSeparation.isFinite() && rankSeparation >= 0) { "rankSeparation must be finite and non-negative" }
        require(marginX.isFinite() && marginX >= 0) { "marginX must be finite and non-negative" }
        require(marginY.isFinite() && marginY >= 0) { "marginY must be finite and non-negative" }
    }
}

data class LayoutResult(val width: Double, val height: Double)

typealias DagreGraph = Graph<DagreNodeLabel, DagreEdgeLabel>

sealed class GraphException(message: String) : IllegalArgumentException(message) {
    class ParentCycle(val parent: String, val child: String) :
        GraphException("Setting $parent as parent of $child would create a cycle")

    class NamedEdgeRequiresMultigraph :
        GraphException("Cannot set a named edge when multigraph is false")

    class ParentRequiresCompoundGraph :
        GraphException("Cannot set a parent in a non-compound graph")

    class RectangleIntersectionAtCenter :
        GraphException("Cannot find a rectangle intersection for its center point")
}
