package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sub-phase 5.3 modal colour picker.
 *
 * Layout (top → bottom):
 *  1. **Recents row** — twelve most-recent custom colours, one-tap apply.
 *  2. **Hue ring + SL square** — `hue` selected by dragging around the ring;
 *     `saturation` and `lightness` selected by dragging inside the inscribed
 *     square. The square's background is the live hue.
 *  3. **Alpha slider** — `0..255` mapped to a translucent → opaque gradient.
 *  4. **Hex input + preview swatch** — accepts `#RRGGBB` and `#AARRGGBB`,
 *     rejects malformed input inline (the colour preview holds the last
 *     valid value).
 *  5. **Confirm / Cancel** — `Confirm` returns the ARGB int via [onConfirm]
 *     and dismisses; `Cancel` just dismisses.
 *
 * Performance: the picker is a transient sheet, so we don't bother
 * minimizing recomposition beyond the per-channel local `mutableFloatStateOf`
 * pattern. Anything more would burn complexity for no win.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    initialColorArgb: Int,
    recents: List<Int>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Decompose the initial colour into HSL + alpha so the wheel and SL
    // square land on the user's existing pick.
    val initialHsl = remember(initialColorArgb) { argbToHsl(initialColorArgb) }
    var hue by remember { mutableFloatStateOf(initialHsl.hue) }
    var saturation by remember { mutableFloatStateOf(initialHsl.saturation) }
    var lightness by remember { mutableFloatStateOf(initialHsl.lightness) }
    var alpha by remember { mutableFloatStateOf((initialColorArgb ushr 24) / 255f) }
    var hexText by remember(initialColorArgb) {
        mutableStateOf(formatHex(initialColorArgb))
    }
    var hexError by remember { mutableStateOf(false) }

    val currentColor by remember {
        derivedStateOf {
            hslaToArgb(hue, saturation, lightness, alpha)
        }
    }

    // Re-sync the hex field when the user manipulates the wheel / sliders so
    // the textual representation stays in lockstep.
    LaunchedEffect(currentColor) {
        if (!hexError) hexText = formatHex(currentColor)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Custom colour",
                style = MaterialTheme.typography.titleMedium,
            )

            if (recents.isNotEmpty()) {
                RecentsRow(
                    recents = recents,
                    onPick = { picked ->
                        val hsl = argbToHsl(picked)
                        hue = hsl.hue
                        saturation = hsl.saturation
                        lightness = hsl.lightness
                        alpha = (picked ushr 24) / 255f
                        hexError = false
                        hexText = formatHex(picked)
                    },
                )
            }

            HueRingWithSLSquare(
                hue = hue,
                saturation = saturation,
                lightness = lightness,
                onHueChanged = { hue = it },
                onSLChanged = { s, l -> saturation = s; lightness = l },
            )

            AlphaSlider(
                hue = hue,
                saturation = saturation,
                lightness = lightness,
                alpha = alpha,
                onAlphaChanged = { alpha = it },
            )

            HexRow(
                hex = hexText,
                error = hexError,
                previewArgb = currentColor,
                initialArgb = initialColorArgb,
                onHexChanged = { text ->
                    hexText = text
                    val parsed = parseHex(text)
                    if (parsed == null) {
                        hexError = true
                    } else {
                        hexError = false
                        val hsl = argbToHsl(parsed)
                        hue = hsl.hue
                        saturation = hsl.saturation
                        lightness = hsl.lightness
                        alpha = (parsed ushr 24) / 255f
                    }
                },
                onRestoreInitial = {
                    hue = initialHsl.hue
                    saturation = initialHsl.saturation
                    lightness = initialHsl.lightness
                    alpha = (initialColorArgb ushr 24) / 255f
                    hexError = false
                    hexText = formatHex(initialColorArgb)
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(currentColor) },
                    enabled = !hexError,
                ) { Text("Use colour") }
            }
        }
    }
}

@Composable
private fun RecentsRow(
    recents: List<Int>,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Recent",
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            recents.take(RecentColorsStoreLimit).forEach { argb ->
                // Full-height 28 dp tap target around the 24 dp visual — the
                // bare circles were the smallest targets on the sheet.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onPick(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(0.5.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun HueRingWithSLSquare(
    hue: Float,
    saturation: Float,
    lightness: Float,
    onHueChanged: (Float) -> Unit,
    onSLChanged: (Float, Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val sizePx = with(LocalDensity.current) { maxWidth.toPx() }
        val ringThickness = sizePx * 0.10f
        val outerR = sizePx * 0.5f
        val innerR = outerR - ringThickness
        // The SL square is inscribed in the inner circle. Side = inner * √2.
        val squareSide = innerR * 1.4142f
        val squareHalf = squareSide * 0.5f
        val center = Offset(outerR, outerR)

        val sweep = remember {
            Brush.sweepGradient(
                colors = listOf(
                    Color.hsl(0f, 1f, 0.5f),
                    Color.hsl(60f, 1f, 0.5f),
                    Color.hsl(120f, 1f, 0.5f),
                    Color.hsl(180f, 1f, 0.5f),
                    Color.hsl(240f, 1f, 0.5f),
                    Color.hsl(300f, 1f, 0.5f),
                    Color.hsl(360f, 1f, 0.5f),
                ),
            )
        }

        // Hue ring layer.
        Canvas(
            modifier = Modifier
                .size(maxWidth)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = hypot(pos.x - center.x, pos.y - center.y)
                            if (r in innerR..outerR) {
                                onHueChanged(angleOf(center, pos))
                            }
                        },
                    ) { change, _ ->
                        val r = hypot(change.position.x - center.x, change.position.y - center.y)
                        // Once the user begins on the ring we keep tracking
                        // even outside its strict thickness — a finger drifts.
                        if (r > innerR * 0.5f) {
                            onHueChanged(angleOf(center, change.position))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val r = hypot(pos.x - center.x, pos.y - center.y)
                        if (r in innerR..outerR) {
                            onHueChanged(angleOf(center, pos))
                        }
                    }
                },
        ) {
            // Draw the hue ring as a filled outer circle minus an inner disc.
            drawCircle(
                brush = sweep,
                radius = outerR,
                center = center,
            )
            drawCircle(
                color = Color.White,
                radius = innerR,
                center = center,
            )
            // Hue thumb.
            val thumbAngle = Math.toRadians(hue.toDouble()).toFloat()
            val midR = (outerR + innerR) * 0.5f
            val thumbCenter = Offset(
                x = center.x + midR * cos(thumbAngle),
                y = center.y + midR * sin(thumbAngle),
            )
            drawCircle(
                color = Color.White,
                radius = ringThickness * 0.45f,
                center = thumbCenter,
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.6f),
                radius = ringThickness * 0.45f,
                center = thumbCenter,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )
        }

        // SL square — separate Canvas inside the ring.
        val squareSideDp = with(LocalDensity.current) { squareSide.toDp() }
        Box(
            modifier = Modifier
                .size(squareSideDp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            Canvas(
                modifier = Modifier
                    .size(squareSideDp)
                    .pointerInput(hue) {
                        detectDragGestures { change, _ ->
                            val s = (change.position.x / size.width).coerceIn(0f, 1f)
                            // Lightness: top is light, bottom is dark.
                            val l = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            onSLChanged(s, l)
                        }
                    }
                    .pointerInput(hue) {
                        detectTapGestures { pos ->
                            val s = (pos.x / size.width).coerceIn(0f, 1f)
                            val l = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                            onSLChanged(s, l)
                        }
                    },
            ) {
                // Horizontal: white → pure hue. Vertical: top transparent, bottom black.
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White, Color.hsl(hue, 1f, 0.5f)),
                    ),
                    size = Size(size.width, size.height),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                    ),
                    size = Size(size.width, size.height),
                )
                // SL thumb.
                val thumbX = saturation * size.width
                val thumbY = (1f - lightness) * size.height
                drawCircle(
                    color = Color.White,
                    radius = 8f,
                    center = Offset(thumbX, thumbY),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = 8f,
                    center = Offset(thumbX, thumbY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                )
            }
        }
    }
}

@Composable
private fun AlphaSlider(
    hue: Float,
    saturation: Float,
    lightness: Float,
    alpha: Float,
    onAlphaChanged: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Alpha ${(alpha * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
        )
        // One control: the transparent → opaque gradient *is* the slider
        // track (the slider's own track is made transparent and the thumb
        // rides directly on the gradient). The old layout stacked a
        // decorative gradient strip above a plain slider, which read as two
        // disconnected rows.
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.hsl(hue, saturation, lightness, 0f),
                                Color.hsl(hue, saturation, lightness, 1f),
                            ),
                        ),
                    ),
            )
            Slider(
                value = alpha,
                onValueChange = onAlphaChanged,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun HexRow(
    hex: String,
    error: Boolean,
    previewArgb: Int,
    initialArgb: Int,
    onHexChanged: (String) -> Unit,
    onRestoreInitial: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = hex,
            onValueChange = onHexChanged,
            label = { Text("Hex") },
            singleLine = true,
            isError = error,
            supportingText = {
                if (error) Text("Use #RRGGBB or #AARRGGBB")
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier.weight(1f),
        )
        // Old-vs-new comparison swatch: left half is the colour the sheet
        // opened with (tap to restore it), right half tracks the live pick —
        // a single preview circle gave nothing to compare against.
        Row(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
                .clickable(onClick = onRestoreInitial),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(initialArgb)),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(previewArgb)),
            )
        }
    }
}

/** Mirror of [RecentColorsStore.MAX_ENTRIES] so the picker UI doesn't reach
 *  into the store from a composable. Kept in sync deliberately; if the cap
 *  changes, update both. */
private const val RecentColorsStoreLimit: Int = 12

// ── Colour math ─────────────────────────────────────────────────────────────

internal data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

/** ARGB int → HSL using the standard sRGB formula. Returned hue is in degrees `[0, 360)`. */
internal fun argbToHsl(argb: Int): Hsl {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val l = (maxC + minC) * 0.5f
    val d = maxC - minC
    val s = if (d == 0f) 0f
    else d / (1f - kotlin.math.abs(2f * l - 1f)).coerceAtLeast(1e-6f)
    val h = when {
        d == 0f -> 0f
        maxC == r -> 60f * (((g - b) / d) % 6f)
        maxC == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val hNorm = ((h % 360f) + 360f) % 360f
    return Hsl(hNorm, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
}

internal fun hslaToArgb(h: Float, s: Float, l: Float, alpha: Float): Int {
    val color = Color.hsl(
        hue = (h % 360f + 360f) % 360f,
        saturation = s.coerceIn(0f, 1f),
        lightness = l.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
    return color.toArgb()
}

/** Format an ARGB int as `#AARRGGBB`. */
internal fun formatHex(argb: Int): String =
    "#" + "%08X".format(argb)

/**
 * Parse `#RRGGBB` or `#AARRGGBB` (case-insensitive, leading `#` required).
 * Returns the ARGB int or `null` on any malformed input.
 */
internal fun parseHex(text: String): Int? {
    val raw = text.trim()
    if (!raw.startsWith("#")) return null
    val hex = raw.removePrefix("#")
    return when (hex.length) {
        6 -> runCatching {
            val rgb = hex.toLong(16).toInt()
            (0xFF shl 24) or (rgb and 0x00FFFFFF)
        }.getOrNull()
        8 -> runCatching { hex.toLong(16).toInt() }.getOrNull()
        else -> null
    }
}

/** Convert a screen-space offset to a hue angle `[0, 360)` around [center]. */
private fun angleOf(center: Offset, pos: Offset): Float {
    val dx = pos.x - center.x
    val dy = pos.y - center.y
    val rad = atan2(dy.toDouble(), dx.toDouble())
    val deg = Math.toDegrees(rad).toFloat()
    return ((deg % 360f) + 360f) % 360f
}

