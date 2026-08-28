package com.unifiedmesh.feature.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import javax.inject.Inject

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
 * Draws node positions from both networks as `MT`/`MC` pins over an
 * OpenStreetMap basemap, rendered by osmdroid.
 *
 * ### Offline behaviour
 *
 * A mesh app is most useful exactly where there is no network, so tiles are
 * cached to app-private storage and served from that cache when offline.
 * osmdroid falls back to blank tiles for anything not cached, and the pins are
 * drawn regardless — so an off-grid map degrades to the node plot it used to be
 * rather than failing. Panning an area while connected is what puts it in the
 * cache; there is no pre-fetch.
 *
 * ### Tile usage
 *
 * The OpenStreetMap Foundation's tile policy requires an identifying
 * User-Agent, which [rememberConfiguredMapView] sets to the app's package name.
 * Leaving osmdroid's default sends `osmdroid`, which the OSMF blocks outright.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val nodes by viewModel.positionedNodes.collectAsStateWithLifecycle()
    val mapView = rememberConfiguredMapView()
    val framed = remember { mutableStateOf(false) }

    val markerColors = MeshProtocol.entries.associateWith { it.markerColor().toArgb() }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.overlays.clear()
                    nodes.forEach { node ->
                        val nodePosition = node.position ?: return@forEach
                        val marker = Marker(view).apply {
                            position = GeoPoint(nodePosition.latitude, nodePosition.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = pinDrawable(
                                view.context,
                                markerColors.getValue(node.protocol),
                                node.protocol.shortLabel,
                            )
                            title = node.displayName
                            snippet = buildString {
                                append(node.protocol.displayName)
                                node.hopsAway?.let { append("  ·  $it hop${if (it == 1) "" else "s"}") }
                                node.batteryLevel?.let { append("  ·  $it%") }
                            }
                        }
                        view.overlays.add(marker)
                    }
                    if (!framed.value && nodes.isNotEmpty()) {
                        framed.value = true
                        view.zoomToNodes(nodes)
                    }
                    view.invalidate()
                },
            )

            // An overlay rather than a replacement for the map: an empty node
            // list is normal, and hiding the basemap behind a full-screen
            // message made a working map look broken.
            if (nodes.isEmpty()) {
                Card(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("No positions yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Nodes appear here when they report a position. " +
                                "Many nodes never do, so this map is often partial.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        MapLegend(nodes)
    }
}

/**
 * Creates the [MapView] once and ties it to the composition's lifecycle.
 *
 * osmdroid is a View-based library with its own `onResume`/`onPause` contract —
 * they start and stop the tile downloader threads. Missing them leaks those
 * threads for the life of the process, which on a screen the operator opens and
 * closes repeatedly adds up.
 */
@Composable
private fun rememberConfiguredMapView(): MapView {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        Configuration.getInstance().apply {
            // load() first: it restores osmdroid's own persisted settings, and
            // would otherwise overwrite everything set below on first use.
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            // Required by the OSM Foundation tile policy: the default value is
            // blocked outright. The package name identifies the app without
            // identifying the operator.
            userAgentValue = context.packageName
            // App-private storage: needs no permission on any supported API
            // level, and goes away with the app rather than being left behind.
            // osmdroid does not create these itself.
            osmdroidBasePath = context.filesDir.resolve("osmdroid").apply { mkdirs() }
            osmdroidTileCache = context.cacheDir.resolve("osmdroid-tiles").apply { mkdirs() }
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // The built-in +/- buttons duplicate pinch-zoom and cover the map.
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(4.0)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    return mapView
}

/**
 * Frames the nodes.
 *
 * Guarded by a flag the caller owns rather than [android.view.View.setTag],
 * whose int overload throws unless the key is a real resource id. It runs only
 * on the first non-empty list: re-framing on every update would yank the view
 * back from wherever the operator had panned every time a node reported a new
 * position.
 */
private fun MapView.zoomToNodes(nodes: List<MeshNode>) {
    val points = nodes.mapNotNull { it.position }.map { GeoPoint(it.latitude, it.longitude) }
    if (points.isEmpty()) return
    if (points.size == 1) {
        controller.setZoom(13.0)
        controller.setCenter(points.first())
        return
    }
    // A small pad keeps pins off the very edge of the viewport.
    val box = BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.3f)
    // post: zoomToBoundingBox needs a laid-out view to know its own size.
    post { zoomToBoundingBox(box, false) }
}

/**
 * A coloured pin carrying the protocol's two-letter label.
 *
 * Drawn in code rather than shipped as two drawables so the label and the colour
 * cannot drift apart from [MeshProtocol].
 */
private fun pinDrawable(context: Context, color: Int, label: String): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (26 * density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    canvas.drawCircle(radius, radius, radius - density, fill)

    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    canvas.drawCircle(radius, radius, radius - density, ring)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = 11f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    val bounds = Rect()
    text.getTextBounds(label, 0, label.length, bounds)
    canvas.drawText(label, radius, radius + bounds.height() / 2f, text)

    return BitmapDrawable(context.resources, bitmap)
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
            Text(
                text = "Map data © OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun MeshProtocol.markerColor(): Color = when (this) {
    MeshProtocol.MESHTASTIC -> Color(0xFF2F6FED)
    MeshProtocol.MESHCORE -> Color(0xFF7B49C9)
}

/**
 * Seam for an offline basemap.
 *
 * osmdroid renders online Mapnik tiles cached to disk. A deployment that needs
 * a guaranteed-offline map — tiles pre-loaded before leaving signal — would
 * implement this over an MBTiles or sqlite archive and hand it to
 * `MapView.setTileSource`. Declared here so the extension point is explicit.
 */
interface MapTileSource {
    /** Returns a tile for the standard slippy-map addressing scheme, or null if unavailable. */
    suspend fun tile(zoom: Int, x: Int, y: Int): androidx.compose.ui.graphics.ImageBitmap?
}
