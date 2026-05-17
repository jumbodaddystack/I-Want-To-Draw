package com.aichat.sandbox.data.notes

import android.util.Log
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device handwriting recognition wrapping ML Kit Digital Ink (sub-phase
 * 2.3 of `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * Two responsibilities:
 *  - Surface the model-download lifecycle as an observable [state] so the UI
 *    can show progress on first run (download itself is opaque — ML Kit's API
 *    doesn't emit progress events, so we only flip between `Downloading(0f)`
 *    and `Downloading(1f)` around the awaited task instead of faking a curve).
 *  - Convert Phase-1 stroke `NoteItem`s into an [Ink] and invoke the
 *    recognizer, returning a friendly [OcrResult] (never throws — failures
 *    surface as [OcrResult.EMPTY] plus a log).
 *
 * ### Synthetic timestamps
 * The Phase-1 codec deliberately drops per-point timestamps, but ML Kit wants
 * them for stroke-order context. We synthesize evenly-spaced timestamps at the
 * S-Pen's ~240 Hz sampling rate, accumulating across strokes so the model sees
 * a monotonically increasing time axis. Accuracy is unaffected for printed
 * writing; cursive may degrade slightly versus capturing real timing. Bumping
 * `StrokeCodec` to embed timestamps is a deliberate `schemaVersion` follow-up.
 */
/**
 * Minimal recognizer surface used by callers (currently `NoteAiService` in
 * sub-phase 2.5) that need to substitute the ML Kit-backed implementation
 * in unit tests.
 */
interface HandwritingRecognizer {
    suspend fun recognize(
        strokes: List<NoteItem>,
        locale: String = HandwritingOcr.DEFAULT_LOCALE,
    ): OcrResult
}

@Singleton
class HandwritingOcr @Inject constructor() : HandwritingRecognizer {

    sealed interface OcrModelState {
        data object NotDownloaded : OcrModelState
        data class Downloading(val progress: Float) : OcrModelState
        data object Ready : OcrModelState
        data class Failed(val reason: String) : OcrModelState
    }

    private val _state = MutableStateFlow<OcrModelState>(OcrModelState.NotDownloaded)
    val state: StateFlow<OcrModelState> = _state.asStateFlow()

    // One in-flight download at a time, regardless of how many callers ask.
    private val downloadLock = Mutex()
    // Recognizer instances are heavy; cache per-locale and reuse.
    private val recognizers = ConcurrentHashMap<String, DigitalInkRecognizer>()
    private val remoteModelManager = RemoteModelManager.getInstance()

    /**
     * Block until the recognition model for [locale] is on-device. Returns
     * `true` on success (model present or downloaded), `false` if the locale
     * is unsupported, Play Services is missing, or the download failed. Safe
     * to call repeatedly — already-downloaded models short-circuit.
     */
    suspend fun ensureModelReady(locale: String = DEFAULT_LOCALE): Boolean {
        val model = modelFor(locale)
        if (model == null) {
            _state.value = OcrModelState.Failed("Unsupported locale: $locale")
            return false
        }
        return downloadLock.withLock {
            try {
                val downloaded = remoteModelManager.isModelDownloaded(model).await()
                if (downloaded) {
                    _state.value = OcrModelState.Ready
                    return@withLock true
                }
                _state.value = OcrModelState.Downloading(0f)
                remoteModelManager
                    .download(model, DownloadConditions.Builder().build())
                    .await()
                _state.value = OcrModelState.Downloading(1f)
                _state.value = OcrModelState.Ready
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Digital Ink model download failed for $locale", t)
                _state.value = OcrModelState.Failed(t.message ?: t.javaClass.simpleName)
                false
            }
        }
    }

    /**
     * Recognize handwriting in [strokes], which may include non-stroke items
     * (text boxes etc.) — those are filtered out. Returns [OcrResult.EMPTY] if
     * there are no strokes, the model isn't available, or recognition throws.
     */
    override suspend fun recognize(
        strokes: List<NoteItem>,
        locale: String,
    ): OcrResult {
        if (strokes.none { it.kind == STROKE_KIND }) return OcrResult.EMPTY
        return try {
            if (!ensureModelReady(locale)) return OcrResult.EMPTY
            val recognizer = recognizerFor(locale) ?: return OcrResult.EMPTY
            val ink = buildInk(strokes) ?: return OcrResult.EMPTY
            val result = recognizer.recognize(ink).await()
            mapResult(result)
        } catch (t: Throwable) {
            Log.w(TAG, "Digital Ink recognize failed", t)
            OcrResult.EMPTY
        }
    }

    private fun mapResult(result: RecognitionResult): OcrResult {
        val candidates = result.candidates
        if (candidates.isEmpty()) return OcrResult.EMPTY
        val best = candidates.first()
        val text = best.text ?: ""
        val confidence = best.score ?: 0f
        val perWord = candidates.map { WordCandidate(it.text ?: "", it.score) }
        return OcrResult(text = text, confidence = confidence, perWord = perWord)
    }

    private fun recognizerFor(locale: String): DigitalInkRecognizer? {
        recognizers[locale]?.let { return it }
        val model = modelFor(locale) ?: return null
        val recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        recognizers[locale] = recognizer
        return recognizer
    }

    private fun modelFor(locale: String): DigitalInkRecognitionModel? {
        // ML Kit can throw `MlKitException` (e.g. malformed BCP-47 tag) in
        // addition to returning null for unsupported locales — catch both.
        val identifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(locale)
        } catch (t: Throwable) {
            Log.w(TAG, "Unsupported Digital Ink locale: $locale", t)
            null
        } ?: return null
        return DigitalInkRecognitionModel.builder(identifier).build()
    }

    companion object {
        const val DEFAULT_LOCALE: String = "en-US"

        private const val STROKE_KIND: String = "stroke"
        private const val TAG: String = "HandwritingOcr"

        // S-Pen samples at ~240 Hz; synthesize timestamps at the same cadence
        // since the Phase-1 codec drops them. See class kdoc.
        internal const val SAMPLING_RATE_HZ: Int = 240

        /**
         * Build an [Ink] from Phase-1 stroke items. Timestamps accumulate
         * monotonically across strokes so the recognizer sees realistic
         * temporal order. Returns `null` if no point survived filtering.
         *
         * Visible to tests so the conversion can be exercised without Play
         * Services.
         */
        internal fun buildInk(strokes: List<NoteItem>): Ink? {
            val inkBuilder = Ink.builder()
            var globalSample = 0L
            var addedAny = false
            for (item in strokes) {
                if (item.kind != STROKE_KIND) continue
                val samples = StrokeCodec.decode(item.payload)
                val sampleCount = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
                if (sampleCount == 0) continue
                val strokeBuilder = Ink.Stroke.builder()
                for (i in 0 until sampleCount) {
                    val base = i * StrokeCodec.FLOATS_PER_SAMPLE
                    val x = samples[base]
                    val y = samples[base + 1]
                    val t = (globalSample * 1000L) / SAMPLING_RATE_HZ
                    strokeBuilder.addPoint(Ink.Point.create(x, y, t))
                    globalSample++
                }
                inkBuilder.addStroke(strokeBuilder.build())
                addedAny = true
            }
            return if (addedAny) inkBuilder.build() else null
        }
    }
}
