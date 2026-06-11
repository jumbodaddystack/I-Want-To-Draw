package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StickyCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Sub-phase 7.1 — compact, model-friendly JSON view of a note's item set.
 *
 * Used by the EDIT branch of [NoteAiService] to give the model an addressable
 * inventory of items alongside the rasterised PNG. The image stays the
 * lossless representation; this JSON is the *index* the model edits against.
 *
 * Notes on the wire format:
 *  - Each item gets a short id (`s_001`, `h_001`, `t_001`, `i_001`) instead of
 *    the on-disk UUID — saves a lot of bytes per long selection, and the model
 *    handles short ids better. [SerializedCanvas.idMap] translates back.
 *  - Stroke points are downsampled when `count > MAX_POINTS_PER_STROKE` so a
 *    very long stroke doesn't dominate the payload. Strokes that were sampled
 *    get `pointsDownsampled: true` so the model knows the geometry is
 *    approximate.
 *  - Whole-doc soft cap: [MAX_JSON_BYTES]. When exceeded we drop items in
 *    (layer.ordinal asc, points.size desc) order — least-visible / largest
 *    first — until under the cap. The image still carries the dropped strokes
 *    visually; only their addressable handles disappear from the JSON.
 *  - Locked layers are excluded entirely. The system message tells the model
 *    it can't touch them, but defense in depth: don't even hand them over.
 */
object VectorCanvasJson {

    const val SCHEMA: Int = 1

    /** Downsample threshold per stroke. */
    const val MAX_POINTS_PER_STROKE: Int = 64

    /** Soft cap on the encoded JSON size. ~ 180 KB. */
    const val MAX_JSON_BYTES: Int = 180 * 1024

    /** Result of a [serialize] call. */
    data class SerializedCanvas(
        val json: String,
        /** short-id → on-disk UUID, for translating model replies back. */
        val idMap: Map<String, String>,
        /** short-layer-id → on-disk UUID. */
        val layerMap: Map<String, String>,
        /** Items that survived the size cap, in serialised order. */
        val includedItemIds: List<String>,
        /** Items that were dropped by the size cap (their on-disk ids). */
        val droppedItemIds: List<String>,
    )

    /**
     * Serialise [items] to model-readable JSON.
     *
     * @param items   the candidate items (caller should already have applied
     *                any selection filter).
     * @param bounds  axis-aligned bounds rectangle to include in the JSON. If
     *                null the bounds field is omitted.
     * @param layers  per-note layers. Locked layers are dropped from the
     *                output entirely (and any items on them are dropped too).
     */
    fun serialize(
        items: List<NoteItem>,
        bounds: FloatArray?,
        layers: List<NoteLayer>,
    ): SerializedCanvas {
        // Drop locked layers and any items that reference them.
        val unlockedLayers = layers.filterNot { it.locked }
        val unlockedLayerIds: Set<String> = unlockedLayers.mapTo(HashSet()) { it.id }
        val visibleItems = items.filter { it.layerId == null || it.layerId in unlockedLayerIds }

        // Build short-id maps. Each kind has its own counter.
        val idMap = LinkedHashMap<String, String>()
        val reverseMap = HashMap<String, String>()
        val layerMap = LinkedHashMap<String, String>()
        val reverseLayerMap = HashMap<String, String>()

        unlockedLayers.sortedBy { it.ordinal }.forEachIndexed { i, layer ->
            val sid = "L${i + 1}"
            layerMap[sid] = layer.id
            reverseLayerMap[layer.id] = sid
        }
        var strokeSeq = 0
        var shapeSeq = 0
        var textSeq = 0
        var imageSeq = 0
        var stickySeq = 0
        var connectorSeq = 0
        visibleItems.forEach { item ->
            val short = when (item.kind) {
                NoteItem.KIND_STROKE -> "s_${(++strokeSeq).pad()}"
                Shape.KIND -> "h_${(++shapeSeq).pad()}"
                TextItemCodec.KIND -> "t_${(++textSeq).pad()}"
                NoteItem.KIND_IMAGE -> "i_${(++imageSeq).pad()}"
                StickyCodec.KIND -> "n_${(++stickySeq).pad()}"
                ConnectorCodec.KIND -> "c_${(++connectorSeq).pad()}"
                else -> "x_${(item.id.hashCode() and 0xFFFF).toString(16)}"
            }
            idMap[short] = item.id
            reverseMap[item.id] = short
        }

        // Encode every item into a JsonObject.
        val encoded: List<EncodedItem> = visibleItems.mapNotNull { item ->
            encodeItem(item, reverseMap, reverseLayerMap)
        }

        // Build the doc, then drop items until under the soft cap.
        var working = encoded.toMutableList()
        val dropped = ArrayList<String>()
        var json = buildJson(working, bounds, layerMap, layers)
        while (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES && working.isNotEmpty()) {
            // Order to drop: lowest layer ordinal first (background highlighters
            // ship at ordinal=-1 by convention), then largest payload first.
            val victim = working
                .withIndex()
                .minByOrNull { (_, e) -> e.dropPriority }!!
            working.removeAt(victim.index)
            dropped += victim.value.itemId
            json = buildJson(working, bounds, layerMap, layers)
        }

        // Trim id-map down to surviving items (dropped ones don't appear in
        // the JSON; keeping them in the map would mislead the parser).
        val survivingItemIds: Set<String> = working.mapTo(HashSet()) { it.itemId }
        val trimmedIdMap = idMap.filterValues { it in survivingItemIds }

        return SerializedCanvas(
            json = json,
            idMap = trimmedIdMap,
            layerMap = layerMap,
            includedItemIds = working.map { it.itemId },
            droppedItemIds = dropped,
        )
    }

    // ---- internals ----

    private data class EncodedItem(
        val itemId: String,
        val obj: JsonObject,
        /**
         * Smaller priority = drop sooner. Combination of layer ordinal (lower
         * layers go first) and payload size (bigger goes first within a tie).
         */
        val dropPriority: Long,
    )

    private fun buildJson(
        items: List<EncodedItem>,
        bounds: FloatArray?,
        layerMap: Map<String, String>,
        layers: List<NoteLayer>,
    ): String {
        val root = JsonObject()
        root.addProperty("schema", SCHEMA)
        if (bounds != null && bounds.size == 4) {
            val b = JsonObject().apply {
                addProperty("minX", round1(bounds[0]))
                addProperty("minY", round1(bounds[1]))
                addProperty("maxX", round1(bounds[2]))
                addProperty("maxY", round1(bounds[3]))
            }
            root.add("bounds", b)
        }
        val layerArr = JsonArray()
        for ((short, uuid) in layerMap) {
            val layer = layers.firstOrNull { it.id == uuid } ?: continue
            layerArr.add(JsonObject().apply {
                addProperty("id", short)
                addProperty("name", layer.name)
                addProperty("ordinal", layer.ordinal)
                addProperty("opacity", round2(layer.opacityPercent / 100f))
            })
        }
        root.add("layers", layerArr)

        val itemArr = JsonArray()
        for (e in items) itemArr.add(e.obj)
        root.add("items", itemArr)
        return root.toString()
    }

    private fun encodeItem(
        item: NoteItem,
        idMap: Map<String, String>,
        layerMap: Map<String, String>,
    ): EncodedItem? {
        val shortId = idMap[item.id] ?: return null
        val obj = JsonObject().apply {
            addProperty("id", shortId)
            addProperty("kind", item.kind)
            addProperty("color", colorToHex(item.colorArgb))
            addProperty("width", round2(item.baseWidthPx))
            if (item.layerId != null) {
                addProperty("layer", layerMap[item.layerId] ?: item.layerId)
            } else {
                add("layer", JsonNull.INSTANCE)
            }
        }

        val priorityLayerOrdinal: Long = layerOrdinalFor(item.layerId, layerMap)

        var sizeHint: Int = item.payload.size

        when (item.kind) {
            NoteItem.KIND_STROKE -> {
                obj.addProperty("tool", item.tool ?: "pen")
                val samples = try {
                    StrokeCodec.decode(item.payload)
                } catch (_: Throwable) {
                    return null
                }
                val (pointsJson, downsampled) = encodeStrokePoints(samples)
                obj.add("points", pointsJson)
                if (downsampled) obj.addProperty("pointsDownsampled", true)
                sizeHint = samples.size
            }
            Shape.KIND -> {
                val decoded = try {
                    ShapeCodec.decode(item.payload)
                } catch (_: Throwable) {
                    return null
                }
                writeShape(obj, decoded.shape, decoded.fillArgb)
            }
            TextItemCodec.KIND -> {
                val decoded = try {
                    TextItemCodec.decode(item.payload)
                } catch (_: Throwable) {
                    return null
                }
                obj.addProperty("body", decoded.body)
                obj.addProperty("fontSize", round2(decoded.fontSize))
                obj.addProperty("alignment", alignmentName(decoded.alignment))
                obj.add("matrix", floatArrayToJson(decoded.matrix, 3))
            }
            NoteItem.KIND_IMAGE -> {
                val payload = try {
                    ImageItemCodec.decode(item.payload)
                } catch (_: Throwable) {
                    return null
                }
                obj.addProperty("path", payload.relativePath)
                obj.add("bbox", JsonObject().apply {
                    addProperty("x", round1(payload.minX))
                    addProperty("y", round1(payload.minY))
                    addProperty("w", round1(payload.maxX - payload.minX))
                    addProperty("h", round1(payload.maxY - payload.minY))
                })
                if (payload.rotationRad != 0f) {
                    obj.addProperty("rotation", round2(payload.rotationRad))
                }
            }
            StickyCodec.KIND -> {
                val payload = try {
                    StickyCodec.decode(item.payload)
                } catch (_: Throwable) {
                    return null
                }
                obj.addProperty("fill", colorToHex(payload.fillArgb))
                obj.addProperty("body", payload.body)
                obj.add("bbox", JsonObject().apply {
                    addProperty("x", round1(payload.minX))
                    addProperty("y", round1(payload.minY))
                    addProperty("w", round1(payload.width))
                    addProperty("h", round1(payload.height))
                })
            }
            ConnectorCodec.KIND -> {
                val payload = try {
                    ConnectorCodec.decode(item.payload)
                } catch (_: Throwable) {
                    return null
                }
                // Fallback geometry only — the model gets an addressable
                // handle (recolor / delete / restyle); endpoint re-binding
                // is not an AI-editable surface in v1.
                obj.add("geometry", JsonObject().apply {
                    addProperty("x0", round1(payload.x0))
                    addProperty("y0", round1(payload.y0))
                    addProperty("x1", round1(payload.x1))
                    addProperty("y1", round1(payload.y1))
                })
                obj.addProperty("boundStart", payload.fromItemId != null)
                obj.addProperty("boundEnd", payload.toItemId != null)
            }
            else -> return null
        }

        // Drop priority: small for "drop first" — combine layer ordinal (lower
        // ordinal first) and inverted size (bigger first within tie). Both
        // operands are well below 2^32 so the shift stays bounded.
        val dropPriority = (priorityLayerOrdinal * 1_000_000L) - sizeHint.toLong()
        return EncodedItem(itemId = item.id, obj = obj, dropPriority = dropPriority)
    }

    private fun layerOrdinalFor(layerId: String?, layerMap: Map<String, String>): Long {
        if (layerId == null) return 1_000L  // unlayered items drop last
        val idx = layerMap.entries.indexOfFirst { it.value == layerId }
        return if (idx < 0) -1L else idx.toLong()
    }

    private fun encodeStrokePoints(samples: FloatArray): Pair<JsonArray, Boolean> {
        val stride = StrokeCodec.FLOATS_PER_SAMPLE
        val count = samples.size / stride
        if (count == 0) return JsonArray() to false
        val (indices, downsampled) = pickSampleIndices(count)
        val arr = JsonArray(indices.size)
        for (i in indices) {
            val base = i * stride
            val pt = JsonArray(4)
            pt.add(round1(samples[base]))
            pt.add(round1(samples[base + 1]))
            pt.add(round2(samples[base + 2]))
            pt.add(round2(samples[base + 3]))
            arr.add(pt)
        }
        return arr to downsampled
    }

    private fun pickSampleIndices(count: Int): Pair<IntArray, Boolean> {
        if (count <= MAX_POINTS_PER_STROKE) {
            return IntArray(count) { it } to false
        }
        // Even spacing, always keep first + last.
        val out = IntArray(MAX_POINTS_PER_STROKE)
        val step = (count - 1).toDouble() / (MAX_POINTS_PER_STROKE - 1).toDouble()
        for (i in 0 until MAX_POINTS_PER_STROKE) {
            out[i] = (i * step).toInt().coerceIn(0, count - 1)
        }
        out[MAX_POINTS_PER_STROKE - 1] = count - 1
        return out to true
    }

    private fun writeShape(obj: JsonObject, shape: Shape, fillArgb: Int) {
        if (fillArgb != 0) obj.addProperty("fill", colorToHex(fillArgb))
        when (shape) {
            is Shape.Line -> {
                obj.addProperty("type", "line")
                obj.add("geometry", JsonObject().apply {
                    addProperty("x0", round1(shape.x0))
                    addProperty("y0", round1(shape.y0))
                    addProperty("x1", round1(shape.x1))
                    addProperty("y1", round1(shape.y1))
                })
            }
            is Shape.Rect -> {
                obj.addProperty("type", "rect")
                obj.add("geometry", JsonObject().apply {
                    addProperty("x0", round1(shape.x0))
                    addProperty("y0", round1(shape.y0))
                    addProperty("x1", round1(shape.x1))
                    addProperty("y1", round1(shape.y1))
                    addProperty("r", round1(shape.cornerRadius))
                })
            }
            is Shape.Ellipse -> {
                obj.addProperty("type", "ellipse")
                obj.add("geometry", JsonObject().apply {
                    addProperty("cx", round1(shape.cx))
                    addProperty("cy", round1(shape.cy))
                    addProperty("rx", round1(shape.rx))
                    addProperty("ry", round1(shape.ry))
                    addProperty("rotation", round2(shape.rotationRad))
                })
            }
            is Shape.Arrow -> {
                obj.addProperty("type", "arrow")
                obj.add("geometry", JsonObject().apply {
                    addProperty("x0", round1(shape.x0))
                    addProperty("y0", round1(shape.y0))
                    addProperty("x1", round1(shape.x1))
                    addProperty("y1", round1(shape.y1))
                    addProperty("head", round1(shape.headSize))
                })
            }
            is Shape.Polygon -> {
                obj.addProperty("type", "polygon")
                val pts = JsonArray(shape.points.size / 2)
                var i = 0
                while (i < shape.points.size) {
                    val pt = JsonArray(2)
                    pt.add(round1(shape.points[i]))
                    pt.add(round1(shape.points[i + 1]))
                    pts.add(pt)
                    i += 2
                }
                obj.add("points", pts)
                obj.addProperty("closed", shape.closed)
            }
        }
    }

    private fun floatArrayToJson(arr: FloatArray, decimals: Int): JsonArray {
        val out = JsonArray(arr.size)
        for (v in arr) out.add(if (decimals == 1) round1(v) else round2(v))
        return out
    }

    private fun alignmentName(alignment: Byte): String = when (alignment) {
        TextItemCodec.ALIGN_CENTER -> "center"
        TextItemCodec.ALIGN_RIGHT -> "right"
        else -> "left"
    }

    private fun colorToHex(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "#%02X%02X%02X".format(r, g, b)
    }

    private fun round1(v: Float): Float = (kotlin.math.round(v * 10f) / 10f)
    private fun round2(v: Float): Float = (kotlin.math.round(v * 100f) / 100f)

    private fun Int.pad(): String = this.toString().padStart(3, '0')
}
