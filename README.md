# dagre-kotlin

An idiomatic, dependency-free Kotlin/JVM port of
[dagre](https://github.com/dagrejs/dagre), based on the behavior of the
[`dagre-swift`](https://github.com/lukilabs/dagre-swift) implementation. It computes layered layouts for
directed graphs and is suitable for Android, server, and desktop JVM projects.

## Requirements

- JVM 11 or newer
- Kotlin 2.3.21 when building from source

The published library itself has no Android dependency.

## Quick start

```kotlin
import io.github.bakhtiyork.dagre.*

val graph = DagreGraph(
    GraphOptions(directed = true, multigraph = false, compound = false),
)

graph.setNode("a", DagreNodeLabel(width = 100.0, height = 50.0))
graph.setNode("b", DagreNodeLabel(width = 100.0, height = 50.0))
graph.setEdge("a", "b", DagreEdgeLabel())

val bounds = graph.layout(
    LayoutOptions(
        rankDirection = RankDirection.TOP_TO_BOTTOM,
        nodeSeparation = 50.0,
        rankSeparation = 50.0,
    ),
)

println(graph.node("a")!!.x)
println(graph.edge("a", "b")!!.points)
println("${bounds.width} x ${bounds.height}")
```

Layout mutates only the output fields on the supplied node and edge labels:
node `x`, `y`, `rank`, and `order`, plus edge `x`, `y`, and `points`. Compound
node width and height are also updated to their computed bounds. Input options
and edge constraints are not modified, so the same graph and options may be
laid out repeatedly.

## Capabilities

- Network-simplex, tight-tree, and longest-path ranking
- Greedy and DFS cycle removal
- Barycenter-based crossing minimization
- Brandes–Köpf coordinate assignment and four alignment modes
- Top-to-bottom, bottom-to-top, left-to-right, and right-to-left layouts
- Compound graphs, multigraphs, self-edges, and edge labels
- Deterministic insertion-order traversal and per-graph temporary IDs

## Build

```shell
./gradlew test
./gradlew build
```

## Notes

- Node identifiers are strings.
- Layout requires a directed graph and labels on every node and edge.
- Graph and label instances are mutable and are not safe for concurrent
  mutation. Separate graph instances can be laid out concurrently.
- Algorithm implementation details are internal; the supported entry points
  are `Dagre.layout(graph, options)` and `graph.layout(options)`.

## License

MIT. This project derives from dagrejs/dagre and SwiftDagre; see `LICENSE` and
the source headers for attribution.
