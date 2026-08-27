package com.unifiedmesh.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.database.MessageRepository
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.feature.common.ProtocolTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@HiltViewModel
class MapViewModel @Inject constructor(
    repository: MessageRepository,
) : ViewModel() {

    /**
     * Only nodes that actually reported a position.
     *
     * Most MeshCore contacts and plenty of Meshtastic nodes never send one, so
     * the map is expected to be sparse and must not imply a node is at 0,0.
     */
    val positionedNodes: StateFlow<List<MeshNode>> = repository.observePositionedNodes()
        .map { nodes -> nodes.filter { it.position?.isValid == true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * The unified map.
 *
 * ### What this is, and what it is not
 *
 * This renders node positions from both networks on a pan-and-zoom plot with
 * `MT`/`MC` badges, scaled to the bounding box of whatever positions exist. It
 * deliberately draws **no background tiles**: a tile source means either a Google
 * Maps API key or a network dependency, and a mesh app is most useful exactly
 * where there is no network.
 *
 * The seam for adding tiles is [MapTileSource]. An implementation backed by
 * osmdroid with pre-cached offline tiles would slot in underneath this plot
 * without changing anything above it.
 *
 * The screen degrades gracefully at every step: no positions at all, one
 * position, or positions spread across a continent all render sensibly.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val nodes by viewModel.positionedNodes.collectAsStateWithLifecycle()

    if (nodes.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text("No positions yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Nodes appear here when they report a position. " +
                        "Many nodes never do, so this map is often partial.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        NodePlot(nodes, Modifier.weight(1f).fillMaxWidth())
        MapLegend(nodes)
    }
}

@Composable
private fun NodePlot(nodes: List<MeshNode>, modifier: Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val bounds = remember(nodes) { Bounds.of(nodes) }
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val colors = MeshProtocol.entries.associateWith { it.markerColor() }

    Canvas(
        modifier
            .background(surface)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // Clamped so a stray pinch cannot zoom to a point where every
                    // marker is off-screen and the map looks broken.
                    scale = (scale * zoom).coerceIn(0.5f, 12f)
                    offset += pan
                }
            },
    ) {
        drawGrid(gridColor)
        nodes.forEach { node ->
            val position = node.position ?: return@forEach
            val point = bounds.toCanvas(position.latitude, position.longitude, size) * scale + offset
            drawCircle(
                color = colors.getValue(node.protocol),
                radius = 9f,
                center = point,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 3.5f,
                center = point,
            )
        }
    }
}

private fun DrawScope.drawGrid(color: Color) {
    val step = size.minDimension / 8f
    var x = step
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

@Composable
private fun MapLegend(nodes: List<MeshNode>) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MeshProtocol.entries.forEach { protocol ->
                val count = nodes.count { it.protocol == protocol }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(protocol.markerColor()),
                    )
                    ProtocolTag(protocol)
                    Text(
                        text = "$count node${if (count == 1) "" else "s"} with a position",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun MeshProtocol.markerColor(): Color = when (this) {
    MeshProtocol.MESHTASTIC -> Color(0xFF2F6FED)
    MeshProtocol.MESHCORE -> Color(0xFF7B49C9)
}

/**
 * The bounding box of the plotted nodes.
 *
 * A degenerate box — one node, or several at the same point — would divide by
 * zero, so a minimum span is applied and the single node lands in the middle.
 */
private data class Bounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    fun toCanvas(latitude: Double, longitude: Double, size: androidx.compose.ui.geometry.Size): Offset {
        val padding = 0.1f
        val usableWidth = size.width * (1 - 2 * padding)
        val usableHeight = size.height * (1 - 2 * padding)
        val x = ((longitude - minLon) / (maxLon - minLon)) * usableWidth + size.width * padding
        // Latitude increases northward; canvas y increases downward.
        val y = ((maxLat - latitude) / (maxLat - minLat)) * usableHeight + size.height * padding
        return Offset(x.toFloat(), y.toFloat())
    }

    companion object {
        private const val MIN_SPAN_DEGREES = 0.002

        fun of(nodes: List<MeshNode>): Bounds {
            val positions = nodes.mapNotNull { it.position }
            if (positions.isEmpty()) return Bounds(-1.0, 1.0, -1.0, 1.0)

            var minLat = positions.minOf { it.latitude }
            var maxLat = positions.maxOf { it.latitude }
            var minLon = positions.minOf { it.longitude }
            var maxLon = positions.maxOf { it.longitude }

            if (maxLat - minLat < MIN_SPAN_DEGREES) {
                val centre = (maxLat + minLat) / 2
                minLat = centre - MIN_SPAN_DEGREES / 2
                maxLat = centre + MIN_SPAN_DEGREES / 2
            }
            if (maxLon - minLon < MIN_SPAN_DEGREES) {
                val centre = (maxLon + minLon) / 2
                minLon = centre - MIN_SPAN_DEGREES / 2
                maxLon = centre + MIN_SPAN_DEGREES / 2
            }
            return Bounds(min(minLat, maxLat), max(minLat, maxLat), min(minLon, maxLon), max(minLon, maxLon))
        }
    }
}

/**
 * Seam for a real basemap.
 *
 * Implement this with osmdroid, MapLibre, or Google Maps and draw the result
 * behind the node plot. It is declared here, unused, so that the shape of the
 * extension point is fixed before anyone needs it — and so it is obvious that
 * the tile-less plot is a deliberate v1 choice rather than an oversight.
 */
interface MapTileSource {
    /** Returns a tile for the standard slippy-map addressing scheme, or null if unavailable. */
    suspend fun tile(zoom: Int, x: Int, y: Int): androidx.compose.ui.graphics.ImageBitmap?
}
