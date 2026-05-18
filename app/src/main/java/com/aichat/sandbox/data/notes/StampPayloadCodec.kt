package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * Sub-phase 8.3 — lossless codec for stamp payloads.
 *
 * Stamps need byte-identical round-trip (a smoothed pressure-modulated
 * stroke saved as a stamp must replay with the same pressure curve), so
 * we serialise the raw [NoteItem] payload bytes as Base64 alongside the
 * item's metadata. This mirrors [com.aichat.sandbox.ui.screens.notes.EditorActionCodec]'s
 * item encoding so future readers see the same shape — but the codecs are
 * deliberately separate so undo-log schema bumps don't ripple into the
 * stamp library.
 */
object StampPayloadCodec {

    const val SCHEMA: Int = 1

    /** Parsed stamp contents. */
    data class Parsed(
        val items: List<NoteItem>,
        val bounds: FloatArray,
    )

    /**
     * Build the JSON document persisted as `Stamp.payloadJson`. [bounds]
     * is `[minX, minY, maxX, maxY]` of the original selection — used at
     * insert time to centre the stamp on the supplied world point.
     */
    fun encode(items: List<NoteItem>, bounds: FloatArray): String {
        val root = JsonObject()
        root.addProperty("schema", SCHEMA)
        val b = JsonArray(4)
        for (v in bounds) b.add(v)
        root.add("bounds", b)
        val arr = JsonArray(items.size)
        for (item in items) arr.add(encodeItem(item))
        root.add("items", arr)
        return root.toString()
    }

    /** Parse [json] back into items + bounds. Returns null on malformed input. */
    fun parse(json: String): Parsed? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val schema = root.get("schema")?.asInt ?: return null
            if (schema != SCHEMA) return null
            val boundsArr = root.getAsJsonArray("bounds") ?: return null
            if (boundsArr.size() < 4) return null
            val bounds = FloatArray(4) { i -> boundsArr[i].asFloat }
            val itemsArr = root.getAsJsonArray("items") ?: JsonArray()
            val out = ArrayList<NoteItem>(itemsArr.size())
            for (el in itemsArr) {
                val obj = el as? JsonObject ?: continue
                out += decodeItem(obj)
            }
            Parsed(items = out, bounds = bounds)
        } catch (_: Throwable) {
            null
        }
    }

    private fun encodeItem(item: NoteItem): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", item.id)
        obj.addProperty("kind", item.kind)
        if (item.tool != null) obj.addProperty("tool", item.tool)
        obj.addProperty("colorArgb", item.colorArgb)
        obj.addProperty("baseWidthPx", item.baseWidthPx)
        obj.addProperty("zIndex", item.zIndex)
        obj.addProperty("payload", Base64.getEncoder().encodeToString(item.payload))
        if (item.layerId != null) obj.addProperty("layerId", item.layerId)
        return obj
    }

    private fun decodeItem(obj: JsonObject): NoteItem {
        val toolEl = obj.get("tool")
        return NoteItem(
            id = obj.get("id").asString,
            // Stamp items are reparented on insert; the noteId here is a
            // placeholder that the caller overwrites.
            noteId = "",
            zIndex = obj.get("zIndex")?.asInt ?: 0,
            kind = obj.get("kind").asString,
            tool = if (toolEl == null || toolEl.isJsonNull) null else toolEl.asString,
            colorArgb = obj.get("colorArgb").asInt,
            baseWidthPx = obj.get("baseWidthPx").asFloat,
            payload = Base64.getDecoder().decode(obj.get("payload").asString),
            layerId = null,
        )
    }
}
