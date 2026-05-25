# Claude Code Prompt — Vector Art Tune-Up Phase 1

Paste this prompt into Claude Code from the repository root.

---

You are working in the `AI-Chat-Sandbox` Android/Kotlin repository.

Read these files first:

- `CLAUDE.md`
- `docs/VECTOR_ART_TUNEUP_PLAN.md`
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteVectorDrawableExporter.kt`
- `app/src/main/java/com/aichat/sandbox/data/notes/NoteSvgExporter.kt`
- `app/src/main/java/com/aichat/sandbox/data/notes/VectorCanvasJson.kt`
- `app/src/main/java/com/aichat/sandbox/ui/screens/notes/EditPreviewController.kt`

## Task

Implement **Phase 1: External Vector Foundation** from `docs/VECTOR_ART_TUNEUP_PLAN.md`.

The goal is to add a deterministic, JVM-testable parser/writer/metrics foundation for arbitrary Android VectorDrawable XML. Do not add UI. Do not add AI/network calls. Do not modify the existing note editor behavior.

## New package

Create this package:

```text
app/src/main/java/com/aichat/sandbox/data/vector/
```

Add these files or equivalent split files:

```text
VectorDocument.kt
AndroidVectorDrawableParser.kt
AndroidVectorDrawableWriter.kt
PathDataParser.kt
PathDataFormatter.kt
VectorMetricsAnalyzer.kt
VectorDocumentValidator.kt
```

Add tests under:

```text
app/src/test/java/com/aichat/sandbox/data/vector/
```

Suggested test files:

```text
AndroidVectorDrawableParserTest.kt
AndroidVectorDrawableWriterTest.kt
PathDataParserTest.kt
VectorMetricsAnalyzerTest.kt
```

## Core data model

Implement a model close to this. Adjust as needed for idiomatic Kotlin, but keep the same concepts.

```kotlin
data class VectorDocument(
    val viewport: VectorViewport,
    val root: VectorGroup,
    val warnings: List<VectorWarning> = emptyList(),
    val originalXmlBytes: Int? = null,
)

data class VectorViewport(
    val widthDp: Float,
    val heightDp: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
)

data class VectorGroup(
    val id: String,
    val name: String? = null,
    val rotation: Float? = null,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val scaleX: Float? = null,
    val scaleY: Float? = null,
    val translateX: Float? = null,
    val translateY: Float? = null,
    val children: List<VectorNode>,
)

sealed interface VectorNode {
    val id: String

    data class GroupNode(val group: VectorGroup) : VectorNode {
        override val id: String get() = group.id
    }

    data class PathNode(val path: VectorPath) : VectorNode {
        override val id: String get() = path.id
    }
}

data class VectorPath(
    val id: String,
    val name: String? = null,
    val pathData: String,
    val commands: List<PathCommand>? = null,
    val style: VectorStyle,
)

data class VectorStyle(
    val fillColor: String? = null,
    val fillAlpha: Float? = null,
    val fillType: String? = null,
    val strokeColor: String? = null,
    val strokeAlpha: Float? = null,
    val strokeWidth: Float? = null,
    val strokeLineCap: String? = null,
    val strokeLineJoin: String? = null,
    val strokeMiterLimit: Float? = null,
)

data class VectorWarning(
    val code: String,
    val message: String,
    val nodeId: String? = null,
)
```

Use stable generated IDs when XML names are missing:

```text
root
p_001, p_002, ...
g_001, g_002, ...
```

## Path command model

Implement common Android/SVG path commands:

```kotlin
sealed interface PathCommand {
    val relative: Boolean

    data class MoveTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class LineTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class HorizontalTo(val x: Float, override val relative: Boolean = false) : PathCommand
    data class VerticalTo(val y: Float, override val relative: Boolean = false) : PathCommand
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class SmoothCubicTo(val x2: Float, val y2: Float, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class SmoothQuadTo(val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class ArcTo(val rx: Float, val ry: Float, val xAxisRotation: Float, val largeArc: Boolean, val sweep: Boolean, val x: Float, val y: Float, override val relative: Boolean = false) : PathCommand
    data class Close(override val relative: Boolean = false) : PathCommand
}
```

Parser requirements:

- Parse commands: `M/m`, `L/l`, `H/h`, `V/v`, `C/c`, `S/s`, `Q/q`, `T/t`, `A/a`, `Z/z`.
- Accept comma and whitespace separators.
- Accept compact negative numbers, for example `M10-5L20-10`.
- Accept decimals, leading decimals, and exponent notation, for example `.5`, `1e-3`, `-2.4E+5`.
- Support repeated coordinate groups:
  - `M0 0 10 10` means `MoveTo(0,0)` then `LineTo(10,10)`.
  - `L0 0 10 10` means two line commands.
- Never crash on malformed input. Return a parse result with warnings.

Recommended parser API:

```kotlin
data class PathParseResult(
    val commands: List<PathCommand>,
    val warnings: List<VectorWarning> = emptyList(),
)

object PathDataParser {
    fun parse(pathData: String, nodeId: String? = null): PathParseResult
}
```

Formatter requirements:

- Format parsed commands back to valid `pathData`.
- Use existing style from `NoteSvgExporter.fmt` if accessible and appropriate; otherwise implement local float formatting with no scientific notation and trimmed trailing zeros.
- Preserve relative command casing where possible.

Recommended formatter API:

```kotlin
object PathDataFormatter {
    fun format(commands: List<PathCommand>): String
}
```

## Android VectorDrawable parser

Implement:

```kotlin
object AndroidVectorDrawableParser {
    fun parse(xml: String): VectorDocument
}
```

Requirements:

- Parse the root `<vector>` element.
- Read these attributes:
  - `android:width`
  - `android:height`
  - `android:viewportWidth`
  - `android:viewportHeight`
- Width/height may be `108dp`, `24dp`, or bare numeric strings. Store numeric dp value.
- Parse nested `<group>` elements recursively.
- Parse `<path>` elements.
- Preserve these path attributes:
  - `android:name`
  - `android:pathData`
  - `android:fillColor`
  - `android:fillAlpha`
  - `android:fillType`
  - `android:strokeColor`
  - `android:strokeAlpha`
  - `android:strokeWidth`
  - `android:strokeLineCap`
  - `android:strokeLineJoin`
  - `android:strokeMiterLimit`
- Preserve these group attributes:
  - `android:name`
  - `android:rotation`
  - `android:pivotX`
  - `android:pivotY`
  - `android:scaleX`
  - `android:scaleY`
  - `android:translateX`
  - `android:translateY`
- Unknown tags should not crash. Add `VectorWarning(code = "UNSUPPORTED_TAG", ...)`.
- Malformed path data should not crash. Add `VectorWarning(code = "MALFORMED_PATH_DATA", ...)` or a precise warning from `PathDataParser`.
- Missing required viewport fields should produce warnings and safe defaults.

Implementation note:

Prefer JVM-friendly XML parsing APIs. Avoid Android framework XML classes in the core parser so tests can run as plain JVM unit tests.

## Android VectorDrawable writer

Implement:

```kotlin
object AndroidVectorDrawableWriter {
    fun write(document: VectorDocument): String
}
```

Requirements:

- Emit valid Android VectorDrawable XML.
- Include XML declaration.
- Include `xmlns:android="http://schemas.android.com/apk/res/android"`.
- Emit width/height as `dp`.
- Emit viewport fields.
- Emit groups recursively.
- Emit paths with style attributes only when non-null.
- For paths, prefer `PathDataFormatter.format(commands)` when commands are non-null and non-empty; otherwise use original `pathData`.
- Output should be deterministic so tests can compare strings or reparse results.

## Metrics analyzer

Implement:

```kotlin
data class VectorMetrics(
    val xmlBytes: Int,
    val pathCount: Int,
    val groupCount: Int,
    val commandCount: Int,
    val parsedCommandCount: Int,
    val unsupportedPathCount: Int,
    val estimatedPointCount: Int,
    val colorCounts: Map<String, Int>,
    val strokePathCount: Int,
    val fillPathCount: Int,
    val zeroLengthPathCount: Int,
    val tinySegmentEstimate: Int,
    val duplicateCoordinateEstimate: Int,
    val bounds: VectorBounds?,
    val warnings: List<VectorWarning>,
)

data class VectorBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)

object VectorMetricsAnalyzer {
    fun analyze(document: VectorDocument, xml: String? = null): VectorMetrics
}
```

Phase 1 bounds may be approximate. Handle M/L/H/V exactly. For curves and arcs, using endpoints and control points as an approximate bound is acceptable for now.

`tinySegmentEstimate` and `duplicateCoordinateEstimate` may be best-effort estimates based on adjacent line/control/end points.

## Validator

Implement:

```kotlin
object VectorDocumentValidator {
    fun validate(document: VectorDocument): List<VectorWarning>
}
```

Requirements:

- Warn for non-positive viewport dimensions.
- Warn for path nodes with blank path data.
- Warn for paths with neither fill nor stroke.
- Warn for negative stroke widths.
- Return warnings; do not throw.

## Tests

Add meaningful tests. At minimum:

### PathDataParserTest

- `parseMoveAndLineCommands()`
- `parseCompactNegativeNumbers()`
- `parseDecimalsAndExponentNotation()`
- `parseRepeatedMoveToAsLineTo()`
- `formatRoundTripParsesAgain()`
- `malformedPathReturnsWarningNotException()`

### AndroidVectorDrawableParserTest

- `parseMinimalVector()`
- `parseStrokeAndFillPath()`
- `parseNestedGroup()`
- `unknownTagProducesWarning()`
- `malformedPathProducesWarning()`

### AndroidVectorDrawableWriterTest

- `writeMinimalVector()`
- `writerRoundTripPreservesSupportedFields()`
- `writerOmitsNullStyleAttributes()`

### VectorMetricsAnalyzerTest

- `metricsCountsPathsGroupsCommandsAndColors()`
- `metricsIncludesWarnings()`
- `metricsEstimatesBoundsForBasicCommands()`

## Build and test

Follow `CLAUDE.md` for Android SDK setup if needed.

Run the targeted JVM tests:

```bash
./gradlew :app:testDebugUnitTest --console=plain --tests "com.aichat.sandbox.data.vector.*"
```

If the broader test suite has unrelated known Android framework `not mocked` failures, do not chase them unless your changes caused new failures.

## Constraints

- Keep this phase self-contained.
- Avoid Android framework dependencies in the new parser/writer/metrics code.
- Do not add network calls.
- Do not add UI.
- Do not modify existing note behavior.
- Keep code idiomatic Kotlin and easy to test.
- Prefer small, pure functions.
- Add KDoc for public objects/classes explaining their role in the vector tune-up pipeline.

## Expected final response

When done, summarize:

1. Files added/changed.
2. Tests added.
3. Test command run and result.
4. Any intentionally deferred items.
