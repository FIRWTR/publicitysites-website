package com.unifiedmesh.core.database

import com.unifiedmesh.core.database.dao.DiagnosticDao
import com.unifiedmesh.core.database.entity.DiagnosticEntity
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.DiagnosticCategory
import com.unifiedmesh.core.model.DiagnosticEvent
import com.unifiedmesh.core.model.DiagnosticLevel
import com.unifiedmesh.core.model.MeshProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The developer diagnostics log.
 *
 * ### What must never be logged
 *
 * Message bodies, channel PSKs, MeshCore channel secrets, private keys, and full
 * public keys. The log records *metadata*: frame codes and lengths, port numbers,
 * connection transitions, bridge decisions and their reasons, and error text from
 * the platform. [sanitise] is a backstop, not the primary defence — the call
 * sites are written not to pass sensitive values in the first place.
 *
 * The reason this matters: a diagnostics export is the thing an operator is most
 * likely to paste into a bug report.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    private val dao: DiagnosticDao,
    private val clock: Clock,
) {

    /**
     * Own scope so a log write can never block a radio coroutine or be cancelled
     * by one. Diagnostics are best-effort by design.
     */
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private var writesSinceTrim = 0

    fun log(
        level: DiagnosticLevel,
        category: DiagnosticCategory,
        message: String,
        protocol: MeshProtocol? = null,
        detail: String? = null,
    ) {
        scope.launch {
            runCatching {
                dao.insert(
                    DiagnosticEntity(
                        timestamp = clock.nowMillis(),
                        level = level.name,
                        category = category.name,
                        protocol = protocol,
                        message = sanitise(message),
                        detail = detail?.let(::sanitise),
                    ),
                )
                if (++writesSinceTrim >= TRIM_INTERVAL) {
                    writesSinceTrim = 0
                    dao.trimTo(MAX_ENTRIES)
                }
            }
        }
    }

    fun debug(category: DiagnosticCategory, message: String, protocol: MeshProtocol? = null) =
        log(DiagnosticLevel.DEBUG, category, message, protocol)

    fun info(category: DiagnosticCategory, message: String, protocol: MeshProtocol? = null) =
        log(DiagnosticLevel.INFO, category, message, protocol)

    fun warn(category: DiagnosticCategory, message: String, protocol: MeshProtocol? = null) =
        log(DiagnosticLevel.WARN, category, message, protocol)

    fun error(
        category: DiagnosticCategory,
        message: String,
        protocol: MeshProtocol? = null,
        detail: String? = null,
    ) = log(DiagnosticLevel.ERROR, category, message, protocol, detail)

    fun observe(limit: Int = 500): Flow<List<DiagnosticEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

    suspend fun clear() = dao.clear()

    /**
     * Renders the log as text for sharing.
     *
     * Already-sanitised rows, re-sanitised on the way out: an export leaves the
     * device, so it is worth paying for the check twice.
     */
    suspend fun exportSanitised(limit: Int = 2000): String = buildString {
        appendLine("Unified Mesh diagnostics export")
        appendLine("Entries: up to $limit, newest first")
        appendLine("Message contents and key material are not recorded.")
        appendLine()
        dao.recent(limit).forEach { row ->
            append(row.timestamp)
            append(' ')
            append(row.level.padEnd(5))
            append(' ')
            append(row.category.padEnd(11))
            append(' ')
            append(row.protocol?.shortLabel ?: "--")
            append(' ')
            append(sanitise(row.message))
            row.detail?.let {
                append(" | ")
                append(sanitise(it))
            }
            appendLine()
        }
    }

    private fun DiagnosticEntity.toModel() = DiagnosticEvent(
        id = id,
        timestamp = timestamp,
        level = DiagnosticLevel.entries.firstOrNull { it.name == level } ?: DiagnosticLevel.INFO,
        category = DiagnosticCategory.entries.firstOrNull { it.name == category } ?: DiagnosticCategory.GENERAL,
        protocol = protocol,
        message = message,
        detail = detail,
    )

    private companion object {
        const val MAX_ENTRIES = 5_000
        const val TRIM_INTERVAL = 200

        /**
         * Long hex runs are the shape key material takes on the wire, so any run
         * of 32 hex characters or more is truncated before it reaches the log.
         * Short runs — node ids, six-byte key prefixes, fingerprints — are how an
         * operator correlates entries and are left alone.
         */
        val LONG_HEX = Regex("[0-9a-fA-F]{32,}")

        fun sanitise(value: String): String = LONG_HEX.replace(value) { match ->
            "${match.value.take(8)}...<${match.value.length} hex chars redacted>"
        }
    }
}
