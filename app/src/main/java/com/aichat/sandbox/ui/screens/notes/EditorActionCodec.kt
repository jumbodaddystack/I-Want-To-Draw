package com.aichat.sandbox.ui.screens.notes

import android.util.Log
import com.aichat.sandbox.data.model.NoteItem
import java.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * JSON codec for the editor's undo/redo stacks (sub-phase 5.2).
 *
 * Gson's default polymorphic serialization can't handle the sealed
 * [EditorAction] hierarchy without a [com.google.gson.RuntimeTypeAdapterFactory]
 * dance and an annotation processor. We hand-write a tiny one here:
 *
 *  - Each action serializes to a `{"type": "...", …}` object. The discriminator
 *    is a fixed string (see [TYPE_ADD_ITEMS] etc.) — adding a new variant means
 *    adding a new constant and a `when` case; renaming an existing class
 *    doesn't change the wire format.
 *  - Stroke / text payloads (binary) serialize as base64 inside the
 *    [NoteItem] JSON. Matrices serialize as 9-float arrays in row-major order.
 *  - The encoded document is capped at [MAX_BYTES]. When the cap is exceeded,
 *    we drop the oldest entries from `past` first (FIFO) and only touch
 *    `future` if the cap is still over. The user notices the action drop as
 *    "very old undo entries are gone"; the most recent ones — what they're
 *    most likely to want back — are preserved.
 *  - Decode is fail-soft: malformed / future-schema JSON returns empty stacks
 *    with a logcat warning rather than throwing. The editor still opens.
 *
 * The format is keyed by [SCHEMA_VERSION]; bumping it lets future readers
 * recognize old payloads and convert (or drop) without crashing.
 */
object EditorActionCodec {

    const val SCHEMA_VERSION: Int = 1

    /** Hard cap on the encoded undo log size persisted to the `notes` row. */
    const val MAX_BYTES: Int = 256 * 1024

    private const val TAG = "EditorActionCodec"

    // Type discriminators. Lock these strings: changing them breaks every
    // existing on-disk undo log.
    private const val TYPE_ADD_ITEMS = "AddItems"
    private const val TYPE_REMOVE_ITEMS = "RemoveItems"
    private const val TYPE_TRANSFORM_ITEMS = "TransformItems"
    private const val TYPE_UPDATE_TEXT = "UpdateText"

    /** Result of a successful decode. */
    data class Decoded(
        val past: List<EditorAction>,
        val future: List<EditorAction>,
    ) {
        companion object {
            val EMPTY = Decoded(emptyList(), emptyList())
        }
    }

    /**
     * Encode the past / future stacks. Returns `null` when both stacks are
     * empty (so the caller stores `undoLogJson = null`, keeping rows tidy).
     *
     * Eviction policy when the encoded size exceeds [MAX_BYTES]:
     *  1. Drop the oldest entry of `past` repeatedly.
     *  2. If `past` is empty and we're still over, drop the oldest entry of
     *     `future` (closest to the user's "current head").
     *  3. If both stacks emptied during eviction, return `null`.
     */
    fun encode(past: List<EditorAction>, future: List<EditorAction>): String? {
        if (past.isEmpty() && future.isEmpty()) return null
        val mutablePast = ArrayDeque(past)
        val mutableFuture = ArrayDeque(future)
        while (true) {
            val json = buildJson(mutablePast, mutableFuture)
            val encoded = json.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                return encoded
            }
            if (mutablePast.isNotEmpty()) {
                mutablePast.removeFirst()
            } else if (mutableFuture.isNotEmpty()) {
                mutableFuture.removeFirst()
            } else {
                return null
            }
        }
    }

    /**
     * Decode a previously-stored JSON document. Fail-soft: `null` /
     * malformed / unknown-schema returns [Decoded.EMPTY] with a logcat
     * warning. Unknown action types within a valid envelope are skipped so a
     * one-bad-entry doesn't take down the whole log.
     */
    fun decode(json: String?): Decoded {
        if (json.isNullOrBlank()) return Decoded.EMPTY
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val schema = root.get("schema")?.asInt ?: return warn("missing schema")
            if (schema != SCHEMA_VERSION) {
                // A newer schema written by a future build is treated as
                // "drop the log, don't crash" — same as malformed JSON.
                return warn("unsupported schema=$schema (want $SCHEMA_VERSION)")
            }
            val past = decodeList(root.getAsJsonArray("past"))
            val future = decodeList(root.getAsJsonArray("future"))
            Decoded(past, future)
        } catch (t: Throwable) {
            warn("decode failed: ${t.message}")
        }
    }

    private fun warn(reason: String): Decoded {
        logWarn(reason)
        return Decoded.EMPTY
    }

    private fun logWarn(message: String) {
        // Android `Log.w` throws under plain JUnit (the framework class isn't
        // available off-device); fall back to stderr so unit tests covering
        // the malformed-input branches don't crash the JVM.
        try {
            Log.w(TAG, message)
        } catch (t: Throwable) {
            System.err.println("[$TAG] $message")
        }
    }

    private fun buildJson(
        past: Collection<EditorAction>,
        future: Collection<EditorAction>,
    ): JsonObject {
        val root = JsonObject()
        root.addProperty("schema", SCHEMA_VERSION)
        root.add("past", encodeList(past))
        root.add("future", encodeList(future))
        return root
    }

    private fun encodeList(actions: Collection<EditorAction>): JsonArray {
        val arr = JsonArray(actions.size)
        for (action in actions) arr.add(encodeAction(action))
        return arr
    }

    private fun decodeList(arr: JsonArray?): List<EditorAction> {
        if (arr == null || arr.size() == 0) return emptyList()
        val out = ArrayList<EditorAction>(arr.size())
        for (el in arr) {
            val action = decodeAction(el) ?: continue
            out += action
        }
        return out
    }

    private fun encodeAction(action: EditorAction): JsonObject {
        val obj = JsonObject()
        when (action) {
            is EditorAction.AddItems -> {
                obj.addProperty("type", TYPE_ADD_ITEMS)
                obj.add("items", encodeItems(action.items))
            }
            is EditorAction.RemoveItems -> {
                obj.addProperty("type", TYPE_REMOVE_ITEMS)
                obj.add("items", encodeItems(action.items))
            }
            is EditorAction.TransformItems -> {
                obj.addProperty("type", TYPE_TRANSFORM_ITEMS)
                obj.add("ids", encodeStringList(action.ids))
                obj.add("matrix", encodeFloatArray(action.matrix))
            }
            is EditorAction.UpdateText -> {
                obj.addProperty("type", TYPE_UPDATE_TEXT)
                obj.addProperty("id", action.id)
                obj.addProperty("oldBody", action.oldBody)
                obj.addProperty("newBody", action.newBody)
            }
        }
        return obj
    }

    private fun decodeAction(el: JsonElement): EditorAction? {
        val obj = el as? JsonObject ?: return null
        val type = obj.get("type")?.asString ?: return null
        return try {
            when (type) {
                TYPE_ADD_ITEMS -> EditorAction.AddItems(
                    decodeItems(obj.getAsJsonArray("items"))
                )
                TYPE_REMOVE_ITEMS -> EditorAction.RemoveItems(
                    decodeItems(obj.getAsJsonArray("items"))
                )
                TYPE_TRANSFORM_ITEMS -> EditorAction.TransformItems(
                    ids = decodeStringList(obj.getAsJsonArray("ids")),
                    matrix = decodeFloatArray(obj.getAsJsonArray("matrix")),
                )
                TYPE_UPDATE_TEXT -> EditorAction.UpdateText(
                    id = obj.get("id").asString,
                    oldBody = obj.get("oldBody").asString,
                    newBody = obj.get("newBody").asString,
                )
                else -> {
                    logWarn("skipping unknown action type=$type")
                    null
                }
            }
        } catch (t: Throwable) {
            logWarn("skipping malformed $type: ${t.message}")
            null
        }
    }

    private fun encodeItems(items: List<NoteItem>): JsonArray {
        val arr = JsonArray(items.size)
        for (item in items) arr.add(encodeItem(item))
        return arr
    }

    private fun decodeItems(arr: JsonArray?): List<NoteItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<NoteItem>(arr.size())
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            out += decodeItem(obj)
        }
        return out
    }

    private fun encodeItem(item: NoteItem): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", item.id)
        obj.addProperty("noteId", item.noteId)
        obj.addProperty("zIndex", item.zIndex)
        obj.addProperty("kind", item.kind)
        if (item.tool != null) obj.addProperty("tool", item.tool)
        obj.addProperty("colorArgb", item.colorArgb)
        obj.addProperty("baseWidthPx", item.baseWidthPx)
        obj.addProperty(
            "payload",
            Base64.getEncoder().encodeToString(item.payload),
        )
        return obj
    }

    private fun decodeItem(obj: JsonObject): NoteItem {
        val toolEl = obj.get("tool")
        return NoteItem(
            id = obj.get("id").asString,
            noteId = obj.get("noteId").asString,
            zIndex = obj.get("zIndex").asInt,
            kind = obj.get("kind").asString,
            tool = if (toolEl == null || toolEl.isJsonNull) null else toolEl.asString,
            colorArgb = obj.get("colorArgb").asInt,
            baseWidthPx = obj.get("baseWidthPx").asFloat,
            payload = Base64.getDecoder().decode(obj.get("payload").asString),
        )
    }

    private fun encodeStringList(strings: List<String>): JsonArray {
        val arr = JsonArray(strings.size)
        for (s in strings) arr.add(s)
        return arr
    }

    private fun decodeStringList(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.size())
        for (el in arr) out += el.asString
        return out
    }

    private fun encodeFloatArray(values: FloatArray): JsonArray {
        val arr = JsonArray(values.size)
        for (v in values) arr.add(v)
        return arr
    }

    private fun decodeFloatArray(arr: JsonArray?): FloatArray {
        if (arr == null) return FloatArray(0)
        val out = FloatArray(arr.size())
        for (i in 0 until arr.size()) out[i] = arr[i].asFloat
        return out
    }
}
