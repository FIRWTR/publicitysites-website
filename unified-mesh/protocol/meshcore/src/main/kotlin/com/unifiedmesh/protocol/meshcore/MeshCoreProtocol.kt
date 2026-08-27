package com.unifiedmesh.protocol.meshcore

/**
 * MeshCore companion-radio protocol constants.
 *
 * Every value here is transcribed from the upstream firmware, not inferred:
 * `meshcore-dev/MeshCore`, `examples/companion_radio/MyMesh.cpp` (the `CMD_*`,
 * `RESP_CODE_*` and `PUSH_CODE_*` `#define` blocks) and
 * `src/helpers/BaseSerialInterface.h` for [MAX_FRAME_SIZE]. See
 * docs/PROTOCOL-NOTES.md for the exact commit the transcription was taken from.
 *
 * Do not add a value to this file without a matching line in upstream source.
 */
object MeshCoreProtocol {

    // --- BLE ---------------------------------------------------------------
    //
    // The companion firmware exposes a Nordic UART-style service
    // (src/helpers/esp32/SerialBLEInterface.cpp).

    /** Companion radio service. */
    const val SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    /** App writes command frames here. */
    const val RX_CHARACTERISTIC_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    /** Radio notifies response and push frames here. */
    const val TX_CHARACTERISTIC_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    /** `MAX_FRAME_SIZE` in `src/helpers/BaseSerialInterface.h`. */
    const val MAX_FRAME_SIZE = 176

    /**
     * MTU to request.
     *
     * A frame is one characteristic value, so the ATT MTU has to be at least
     * [MAX_FRAME_SIZE] + 3 bytes of ATT header or frames get truncated. The
     * companion protocol documentation recommends requesting 512.
     */
    const val REQUESTED_MTU = 512

    // --- Commands (app -> radio) -------------------------------------------

    const val CMD_APP_START = 1
    const val CMD_SEND_TXT_MSG = 2
    const val CMD_SEND_CHANNEL_TXT_MSG = 3
    const val CMD_GET_CONTACTS = 4
    const val CMD_GET_DEVICE_TIME = 5
    const val CMD_SET_DEVICE_TIME = 6
    const val CMD_SEND_SELF_ADVERT = 7
    const val CMD_SET_ADVERT_NAME = 8
    const val CMD_ADD_UPDATE_CONTACT = 9
    const val CMD_SYNC_NEXT_MESSAGE = 10
    const val CMD_GET_BATT_AND_STORAGE = 20
    const val CMD_DEVICE_QUERY = 22
    const val CMD_GET_CHANNEL = 31

    // --- Responses (radio -> app) ------------------------------------------

    const val RESP_CODE_OK = 0
    const val RESP_CODE_ERR = 1
    const val RESP_CODE_CONTACTS_START = 2
    const val RESP_CODE_CONTACT = 3
    const val RESP_CODE_END_OF_CONTACTS = 4
    const val RESP_CODE_SELF_INFO = 5
    const val RESP_CODE_SENT = 6
    const val RESP_CODE_CONTACT_MSG_RECV = 7
    const val RESP_CODE_CHANNEL_MSG_RECV = 8
    const val RESP_CODE_CURR_TIME = 9
    const val RESP_CODE_NO_MORE_MESSAGES = 10
    const val RESP_CODE_BATT_AND_STORAGE = 12
    const val RESP_CODE_DEVICE_INFO = 13
    const val RESP_CODE_DISABLED = 15
    const val RESP_CODE_CONTACT_MSG_RECV_V3 = 16
    const val RESP_CODE_CHANNEL_MSG_RECV_V3 = 17
    const val RESP_CODE_CHANNEL_INFO = 18

    // --- Push notifications (radio -> app, unsolicited) ---------------------
    //
    // Everything >= 0x80 is a push rather than a reply to an outstanding command.

    const val PUSH_CODE_ADVERT = 0x80
    const val PUSH_CODE_PATH_UPDATED = 0x81
    const val PUSH_CODE_SEND_CONFIRMED = 0x82
    const val PUSH_CODE_MSG_WAITING = 0x83
    const val PUSH_CODE_RAW_DATA = 0x84
    const val PUSH_CODE_LOGIN_SUCCESS = 0x85
    const val PUSH_CODE_LOGIN_FAIL = 0x86
    const val PUSH_CODE_STATUS_RESPONSE = 0x87
    const val PUSH_CODE_LOG_RX_DATA = 0x88
    const val PUSH_CODE_TRACE_DATA = 0x89
    const val PUSH_CODE_NEW_ADVERT = 0x8A
    const val PUSH_CODE_TELEMETRY_RESPONSE = 0x8B
    const val PUSH_CODE_BINARY_RESPONSE = 0x8C
    const val PUSH_CODE_PATH_DISCOVERY_RESPONSE = 0x8D
    const val PUSH_CODE_CONTROL_DATA = 0x8E
    const val PUSH_CODE_CONTACT_DELETED = 0x8F
    const val PUSH_CODE_CONTACTS_FULL = 0x90

    /** True for response codes the firmware sends unprompted. */
    fun isPush(code: Int): Boolean = code >= 0x80

    // --- Error codes (byte 1 of RESP_CODE_ERR) ------------------------------

    const val ERR_CODE_UNSUPPORTED_CMD = 1
    const val ERR_CODE_NOT_FOUND = 2
    const val ERR_CODE_TABLE_FULL = 3
    const val ERR_CODE_BAD_STATE = 4
    const val ERR_CODE_FILE_IO_ERROR = 5
    const val ERR_CODE_ILLEGAL_ARG = 6

    fun errorMessage(code: Int): String = when (code) {
        ERR_CODE_UNSUPPORTED_CMD -> "unsupported command"
        ERR_CODE_NOT_FOUND -> "not found"
        ERR_CODE_TABLE_FULL -> "table full"
        ERR_CODE_BAD_STATE -> "bad state"
        ERR_CODE_FILE_IO_ERROR -> "file I/O error"
        ERR_CODE_ILLEGAL_ARG -> "illegal argument"
        else -> "error $code"
    }

    // --- Text types (src/helpers/TxtDataHelpers.h) --------------------------

    const val TXT_TYPE_PLAIN = 0
    const val TXT_TYPE_CLI_DATA = 1
    const val TXT_TYPE_SIGNED_PLAIN = 2

    // --- Advert types (src/helpers/AdvertDataHelpers.h) ---------------------

    const val ADV_TYPE_NONE = 0
    const val ADV_TYPE_CHAT = 1
    const val ADV_TYPE_REPEATER = 2
    const val ADV_TYPE_ROOM = 3
    const val ADV_TYPE_SENSOR = 4

    // --- Sizes (src/MeshCore.h) --------------------------------------------

    const val PUB_KEY_SIZE = 32
    const val MAX_PATH_SIZE = 64

    /**
     * Bytes of public key used to address a contact in message frames.
     *
     * `CMD_SEND_TXT_MSG` takes a 6-byte prefix and inbound message frames carry
     * the same 6 bytes, so this is the identity the app keys contacts on.
     */
    const val PUB_KEY_PREFIX_SIZE = 6

    /** `OUT_PATH_UNKNOWN` — flood on send, direct on receive. */
    const val PATH_UNKNOWN = 0xFF

    /**
     * Protocol version this client understands, sent as byte 1 of
     * `CMD_DEVICE_QUERY`.
     *
     * The firmware gates the SNR-carrying `*_V3` message frames on
     * `app_target_ver >= 3`, so anything lower silently loses signal reporting.
     */
    const val APP_TARGET_VERSION = 3

    /**
     * Documented per-message text limit for the companion protocol.
     *
     * Longer text has to be split by the caller; this client refuses rather than
     * truncating mid-word.
     */
    const val MAX_TEXT_LENGTH = 133
}
