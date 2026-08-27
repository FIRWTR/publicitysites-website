package com.unifiedmesh.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.database.DiagnosticsRepository
import com.unifiedmesh.core.model.DiagnosticCategory
import com.unifiedmesh.core.model.DiagnosticEvent
import com.unifiedmesh.core.model.DiagnosticLevel
import com.unifiedmesh.feature.common.ConnectionColors
import com.unifiedmesh.feature.common.ProtocolTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DiagnosticsUiState(
    val events: List<DiagnosticEvent> = emptyList(),
    val category: DiagnosticCategory? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
) : ViewModel() {

    private val category = MutableStateFlow<DiagnosticCategory?>(null)

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        repository.observe(),
        category,
    ) { events, filter ->
        DiagnosticsUiState(
            events = if (filter == null) events else events.filter { it.category == filter },
            category = filter,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun setCategory(value: DiagnosticCategory?) {
        category.value = value
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    /** Produces the sanitised text an operator can attach to a bug report. */
    suspend fun export(): String = repository.exportSanitised()
}

/**
 * Developer diagnostics.
 *
 * Shows connection transitions, BLE events, frame metadata, send results and
 * bridge decisions — including every message the bridge declined and why, which
 * is the only way to tell "the bridge is off" from "the bridge suppressed a
 * duplicate" from "no rule matched".
 *
 * Message bodies and key material are never recorded. See
 * [DiagnosticsRepository].
 */
@Composable
fun DiagnosticsScreen(
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { scope.launch { onExport(viewModel.export()) } }) {
                Text("Export")
            }
            TextButton(onClick = viewModel::clear) { Text("Clear") }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.category == null,
                    onClick = { viewModel.setCategory(null) },
                    label = { Text("All") },
                )
            }
            items(DiagnosticCategory.entries) { category ->
                FilterChip(
                    selected = state.category == category,
                    onClick = { viewModel.setCategory(category) },
                    label = { Text(category.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        HorizontalDivider()

        Text(
            text = "Message contents and encryption keys are never recorded.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.events, key = { it.id }) { event ->
                DiagnosticRow(event)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DiagnosticRow(event: DiagnosticEvent) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = TIME_FORMAT.format(Date(event.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = event.level.name,
                style = MaterialTheme.typography.labelSmall,
                color = when (event.level) {
                    DiagnosticLevel.ERROR -> ConnectionColors.Error
                    DiagnosticLevel.WARN -> ConnectionColors.Reconnecting
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = event.category.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.protocol?.let { ProtocolTag(it) }
        }
        Text(
            text = event.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        event.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
