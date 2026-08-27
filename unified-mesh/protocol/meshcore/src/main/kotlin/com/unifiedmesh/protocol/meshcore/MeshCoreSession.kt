package com.unifiedmesh.protocol.meshcore

import com.unifiedmesh.protocol.api.RadioLinkTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Raised when the radio does not answer a command in time. */
class MeshCoreTimeoutException(command: Int) : Exception("MeshCore command $command timed out")

/** Raised when the radio answers a command with `RESP_CODE_ERR`. */
class MeshCoreErrorException(val errorCode: Int) :
    Exception("MeshCore error: ${MeshCoreProtocol.errorMessage(errorCode)}")

/**
 * Drives the request/response conversation with one MeshCore radio.
 *
 * The companion protocol has no request ids: a reply is matched to a command by
 * its response code, and the firmware documentation is explicit that the client
 * must send one command at a time. [request] and [requestStream] therefore
 * serialise commands behind a mutex and wait for frames the caller recognises.
 *
 * Push frames (response code >= 0x80) can arrive at any moment, including in the
 * middle of a command exchange, so they are routed to [pushes] rather than being
 * mistaken for a reply.
 */
class MeshCoreSession(
    private val transport: RadioLinkTransport,
    private val scope: CoroutineScope,
    private val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
    /** Diagnostics sink. Receives frame metadata only, never message text. */
    private val onFrame: (direction: FrameDirection, code: Int, size: Int) -> Unit = { _, _, _ -> },
) {

    enum class FrameDirection { IN, OUT }

    private val commandMutex = Mutex()

    /** Set while a command is in flight; the reader feeds frames into it. */
    @Volatile
    private var pending: PendingRequest? = null

    private val _pushes = MutableSharedFlow<MeshCoreFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Unsolicited frames: message-waiting tickles, adverts, send confirmations. */
    val pushes: Flow<MeshCoreFrame> = _pushes.asSharedFlow()

    private val _malformedFrames = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Descriptions of frames that failed to decode. Metadata only. */
    val malformedFrames: Flow<String> = _malformedFrames.asSharedFlow()

    private var readerJob: Job? = null

    private class PendingRequest(
        val accept: (MeshCoreFrame) -> Boolean,
        val isTerminal: (MeshCoreFrame) -> Boolean,
        val result: CompletableDeferred<List<MeshCoreFrame>>,
    ) {
        val collected = mutableListOf<MeshCoreFrame>()
    }

    /** Starts consuming inbound frames. Call once, after the transport is open. */
    fun start() {
        if (readerJob != null) return
        readerJob = scope.launch {
            transport.incoming.collect { bytes ->
                val code = if (bytes.isEmpty()) -1 else bytes[0].toInt() and 0xFF
                onFrame(FrameDirection.IN, code, bytes.size)
                val frame = try {
                    MeshCoreCodec.decode(bytes)
                } catch (e: MeshCoreFrameException) {
                    // A frame we cannot parse is logged and dropped: the link is
                    // still healthy and the next frame may well be fine.
                    _malformedFrames.emit(e.message ?: "malformed frame")
                    return@collect
                }
                dispatch(frame)
            }
        }
    }

    /** Stops the reader and fails any in-flight command. */
    fun stop() {
        readerJob?.cancel()
        readerJob = null
        pending?.result?.cancel()
        pending = null
    }

    private fun dispatch(frame: MeshCoreFrame) {
        // A push never satisfies a command, even when a command is in flight.
        if (isPush(frame)) {
            _pushes.tryEmit(frame)
            return
        }
        val waiting = pending
        if (waiting != null) {
            if (waiting.accept(frame)) waiting.collected += frame
            if (waiting.isTerminal(frame)) waiting.result.complete(waiting.collected.toList())
            return
        }
        // A late reply after a timeout, or a frame nobody asked for. Surfacing it
        // as a push lets the adapter still act on it rather than losing it.
        _pushes.tryEmit(frame)
    }

    private fun isPush(frame: MeshCoreFrame): Boolean = when (frame) {
        is MeshCoreFrame.MessagesWaiting,
        is MeshCoreFrame.SendConfirmed,
        is MeshCoreFrame.AdvertReceived,
        is MeshCoreFrame.NewContact,
        is MeshCoreFrame.PathUpdated,
        -> true

        is MeshCoreFrame.Unhandled -> MeshCoreProtocol.isPush(frame.code)
        else -> false
    }

    /**
     * Sends [command] and waits for the first frame satisfying [matches].
     *
     * @throws MeshCoreTimeoutException if no matching frame arrives in time.
     * @throws MeshCoreErrorException if the radio replies `RESP_CODE_ERR`.
     */
    suspend fun request(
        command: ByteArray,
        timeoutMillis: Long = commandTimeoutMillis,
        matches: (MeshCoreFrame) -> Boolean,
    ): MeshCoreFrame = requestStream(
        command = command,
        timeoutMillis = timeoutMillis,
        accept = matches,
        isTerminal = matches,
    ).last()

    /**
     * Sends [command] and collects every frame satisfying [accept] until one
     * satisfies [isTerminal].
     *
     * This is how the contact list is read: `CMD_GET_CONTACTS` is answered by
     * `RESP_CODE_CONTACTS_START`, then one `RESP_CODE_CONTACT` per contact, then
     * `RESP_CODE_END_OF_CONTACTS`. All of it is one exchange, so it has to stay
     * inside one hold of the command mutex.
     *
     * The returned list includes the terminal frame.
     */
    suspend fun requestStream(
        command: ByteArray,
        timeoutMillis: Long = commandTimeoutMillis,
        accept: (MeshCoreFrame) -> Boolean,
        isTerminal: (MeshCoreFrame) -> Boolean,
    ): List<MeshCoreFrame> = commandMutex.withLock {
        val deferred = CompletableDeferred<List<MeshCoreFrame>>()
        // An error or a "feature disabled" reply always ends the wait, whatever
        // the caller asked for; otherwise a rejected command would hang for the
        // full timeout and hold the mutex the whole time.
        val terminal: (MeshCoreFrame) -> Boolean = {
            it is MeshCoreFrame.Error || it is MeshCoreFrame.Disabled || isTerminal(it)
        }
        pending = PendingRequest(
            accept = { accept(it) || it is MeshCoreFrame.Error || it is MeshCoreFrame.Disabled },
            isTerminal = terminal,
            result = deferred,
        )
        try {
            val code = command[0].toInt() and 0xFF
            onFrame(FrameDirection.OUT, code, command.size)
            transport.send(command)
            val frames = withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: throw MeshCoreTimeoutException(code)
            frames.firstOrNull { it is MeshCoreFrame.Error }?.let {
                throw MeshCoreErrorException((it as MeshCoreFrame.Error).code)
            }
            frames.ifEmpty { throw MeshCoreTimeoutException(code) }
        } finally {
            pending = null
        }
    }

    /** Sends a command without waiting for a reply. */
    suspend fun send(command: ByteArray) {
        onFrame(FrameDirection.OUT, command[0].toInt() and 0xFF, command.size)
        transport.send(command)
    }

    companion object {
        /**
         * The companion protocol documentation specifies a five-second command
         * timeout; the radio is a single-threaded microcontroller sharing time
         * with the LoRa modem, so this is deliberately generous.
         */
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 5_000L
    }
}
