package com.aichat.sandbox.data.vector.trace

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.ImageAttachment
import com.aichat.sandbox.data.model.ImageMetadata
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.model.ModelCapabilities
import com.aichat.sandbox.data.remote.ChatStreamer
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorSceneCompiler
import com.aichat.sandbox.data.vector.VectorSceneParser
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.VectorWarning
import com.aichat.sandbox.data.vector.allPaths
import com.google.gson.Gson
import java.util.Base64

/**
 * Provider/model selection + credentials for one AI trace. Resolved by the
 * caller (the UI layer) via `PreferencesManager.credentialsFor()` — mirrors
 * [com.aichat.sandbox.data.vector.VectorRedrawAiRequest]'s `modelId`/`baseUrl`/`apiKey`
 * triple. Kept out of [TraceOptions] so that the provider-agnostic trace knobs
 * stay free of credentials.
 */
data class AiTraceConfig(
    val modelId: String,
    val baseUrl: String,
    val apiKey: String,
)

/**
 * Encodes a row-major ARGB bitmap into PNG bytes. Abstracted (like
 * [com.aichat.sandbox.data.notes.NoteImageRenderer]) so [AiBitmapTracer] stays
 * JVM-testable: production uses [AndroidBitmapPngEncoder] (`android.graphics.Bitmap`),
 * unit tests inject a fake. Returns null when encoding fails.
 */
fun interface BitmapPngEncoder {
    fun encode(pixels: IntArray, width: Int, height: Int): ByteArray?
}

/**
 * Phase 5 (sub-feature 5b) — the **semantic** bitmap tracer.
 *
 * Where [LocalBitmapTracer] is a deterministic threshold/contour pipeline, this
 * backend hands the bitmap to a vision model over the existing multi-provider
 * [ChatStreamer], asks for a clean [com.aichat.sandbox.data.vector.VectorScene]
 * of safe primitives, and validates+compiles the reply through the same
 * [VectorSceneParser]/[VectorSceneCompiler] the redraw workflow uses (so the
 * model never emits raw XML and every object is bounds-checked).
 *
 * The local tracer is the **guaranteed fallback**: a no-vision model, an
 * un-encodable bitmap, a stream error, an unparseable reply, or a scene with no
 * usable shapes all fall back to [local] with a
 * [VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL] note appended — a trace never
 * hard-fails.
 */
class AiBitmapTracer(
    private val streamer: ChatStreamer,
    private val config: AiTraceConfig,
    private val pngEncoder: BitmapPngEncoder,
    private val local: BitmapTracer = LocalBitmapTracer(),
) : BitmapTracer {

    override suspend fun trace(pixels: IntArray, width: Int, height: Int, options: TraceOptions): TraceResult {
        // 1 px → 1 viewport unit, matching the local tracer + the model's brief.
        val fallbackViewport = VectorViewport(
            width.toFloat(), height.toFloat(), width.toFloat(), height.toFloat(),
        )

        if (!ModelCapabilities.of(config.modelId).supportsVision) {
            return fallBackToLocal(pixels, width, height, options, "model '${config.modelId}' has no vision support")
        }
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return fallBackToLocal(pixels, width, height, options, "the bitmap was empty or malformed")
        }

        val png = pngEncoder.encode(pixels, width, height)
            ?: return fallBackToLocal(pixels, width, height, options, "the bitmap could not be encoded")

        val buffer = StringBuilder()
        var streamError: String? = null
        try {
            streamer.sendMessageStream(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                chat = syntheticChat(),
                messages = listOf(buildUserMessage(png, width, height, options)),
            ).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> buffer.append(event.content)
                    is StreamEvent.Error -> streamError = event.message
                    is StreamEvent.Complete -> Unit
                    is StreamEvent.ToolCallDelta -> Unit // impossible: we send no tools.
                }
            }
        } catch (t: Throwable) {
            streamError = t.message ?: "request failed"
        }
        if (streamError != null) {
            return fallBackToLocal(pixels, width, height, options, "the AI request failed ($streamError)")
        }

        val scene = VectorSceneParser.parse(buffer.toString(), fallbackViewport).getOrElse {
            return fallBackToLocal(pixels, width, height, options, "the model's scene could not be parsed")
        }
        val compiled = runCatching { VectorSceneCompiler.compile(scene) }.getOrNull()
            ?: return fallBackToLocal(pixels, width, height, options, "the model's scene could not be compiled")

        val paths: List<VectorPath> = compiled.document.allPaths()
        if (paths.isEmpty()) {
            return fallBackToLocal(pixels, width, height, options, "the model returned no usable shapes")
        }
        return TraceResult(
            paths = paths,
            viewport = compiled.document.viewport,
            warnings = compiled.warnings,
        )
    }

    /** Run the deterministic backend and flag that the AI path was not used. */
    private suspend fun fallBackToLocal(
        pixels: IntArray,
        width: Int,
        height: Int,
        options: TraceOptions,
        reason: String,
    ): TraceResult {
        val result = local.trace(pixels, width, height, options)
        return result.copy(
            warnings = result.warnings + VectorWarning(
                VectorWarning.Codes.TRACE_FELL_BACK_TO_LOCAL,
                "AI trace unavailable ($reason); used the local tracer instead.",
            ),
        )
    }

    private fun buildUserMessage(png: ByteArray, width: Int, height: Int, options: TraceOptions): Message {
        val dataUri = "data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"
        val metadata = gson.toJson(ImageMetadata(images = listOf(ImageAttachment(dataUri = dataUri))))
        return Message(
            chatId = SYNTHETIC_CHAT_ID,
            role = MessageRole.USER.value,
            content = AiTracePrompts.buildUserPrompt(width, height, options.mode),
            contentType = "multimodal",
            metadata = metadata,
        )
    }

    private fun syntheticChat(): Chat = Chat(
        id = SYNTHETIC_CHAT_ID,
        title = "Bitmap Trace",
        model = config.modelId,
        systemMessage = AiTracePrompts.SYSTEM_MESSAGE,
    )

    companion object {
        private const val SYNTHETIC_CHAT_ID = "bitmap-trace-synthetic"
        private val gson = Gson()
    }
}

/**
 * Prompt constants for the AI bitmap trace. Sibling of
 * [com.aichat.sandbox.data.vector.VectorRedrawPrompts]: the model receives an
 * image (not a JSON summary) and must reply with only a fenced `vector-scene`
 * block that [VectorSceneParser] validates — it never sees or returns raw XML.
 */
object AiTracePrompts {

    const val SYSTEM_MESSAGE: String =
        "You trace a raster image into a clean Android-compatible vector scene. " +
            "You receive ONE image and must reconstruct its artwork as a small set " +
            "of primitive objects. You never see or return raw XML.\n\n" +
            "Reply with ONLY a fenced ```vector-scene block of JSON matching this schema:\n\n" +
            "{ \"schema\": 1,\n" +
            "  \"viewport\": { \"widthDp\": W, \"heightDp\": H, \"viewportWidth\": W, \"viewportHeight\": H },\n" +
            "  \"styleIntent\": \"<short phrase>\",\n" +
            "  \"objects\": [ /* primitive objects */ ] }\n\n" +
            "Each object has an \"id\", a \"type\", geometry, and style " +
            "(\"stroke\", \"fill\", \"strokeWidth\", optional \"lineCap\"/\"lineJoin\"/\"strokeAlpha\"/\"fillAlpha\").\n" +
            "Supported types and their geometry:\n" +
            "- path: \"pathData\" (SVG/Android path string)\n" +
            "- line: \"x0\",\"y0\",\"x1\",\"y1\"\n" +
            "- rect: \"x\",\"y\",\"width\",\"height\",optional \"radius\"\n" +
            "- ellipse: \"cx\",\"cy\",\"rx\",\"ry\",optional \"rotation\"\n" +
            "- polygon / polyline: \"points\": [[x,y],...], optional \"closed\"\n\n" +
            "Rules:\n" +
            "- The viewport is the image's pixel size: 1 pixel = 1 viewport unit.\n" +
            "- Keep ALL geometry inside the viewport.\n" +
            "- Keep the object count small; prefer simple geometry over dense paths.\n" +
            "- Preserve the image's shapes and color palette.\n" +
            "- Never return raw XML; only the fenced vector-scene JSON.\n" +
            "- If you cannot trace the image safely, return an empty \"objects\" " +
            "array with a styleIntent summary."

    fun buildUserPrompt(width: Int, height: Int, mode: TraceMode): String = buildString {
        append("Trace the attached image into a vector-scene.\n")
        append("Image size: ${width}x$height pixels — use this as the viewport.\n")
        when (mode) {
            TraceMode.OUTLINE ->
                append("Favor filled shapes that match the solid regions of the image.")
            TraceMode.CENTERLINE ->
                append("Favor stroked lines that follow the strokes/edges of the image.")
        }
    }
}
