package com.aichat.sandbox.ui.components.notes

/**
 * 2D affine matrix helpers for selection transforms (sub-phase 1.8).
 *
 * The matrix is a 9-float row-major buffer matching `android.graphics.Matrix`'s
 * `getValues()` layout — that lets the Compose overlay hand the same buffer
 * to a `Matrix` at render time without copying:
 *
 * ```
 * [scaleX, skewX,  transX,
 *  skewY,  scaleY, transY,
 *  0,      0,      1     ]
 * ```
 *
 * Pure Kotlin so the bake step in [com.aichat.sandbox.ui.screens.notes.EditorAction]
 * stays JVM-testable. Only stroke `x` / `y` are transformed — pressure and
 * tilt are preserved as-is; v1 supports affine transforms only and explicitly
 * out-of-scope is non-uniform scale on pressure/tilt (documented in the
 * parent plan's "Risks").
 */
object StrokeTransform {

    const val SIZE = 9

    val IDENTITY: FloatArray
        get() = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )

    fun isIdentity(m: FloatArray): Boolean {
        if (m.size != SIZE) return false
        return m[0] == 1f && m[1] == 0f && m[2] == 0f &&
            m[3] == 0f && m[4] == 1f && m[5] == 0f &&
            m[6] == 0f && m[7] == 0f && m[8] == 1f
    }

    /** Transforms `(x, y)` → `(x', y')` and writes into `out` (length 2). */
    fun mapPoint(m: FloatArray, x: Float, y: Float, out: FloatArray) {
        out[0] = m[0] * x + m[1] * y + m[2]
        out[1] = m[3] * x + m[4] * y + m[5]
    }

    /**
     * Apply [m] to the `(x, y)` of every sample in a packed
     * `[x, y, p, t, …]` buffer and return a new buffer of the same length.
     * Pressure and tilt copy over untouched.
     */
    fun applyToSamples(m: FloatArray, samples: FloatArray): FloatArray {
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        require(samples.size % s == 0) {
            "samples length must be a multiple of $s (got ${samples.size})"
        }
        val out = FloatArray(samples.size)
        var i = 0
        while (i < samples.size) {
            val x = samples[i]
            val y = samples[i + 1]
            out[i] = m[0] * x + m[1] * y + m[2]
            out[i + 1] = m[3] * x + m[4] * y + m[5]
            out[i + 2] = samples[i + 2]
            out[i + 3] = samples[i + 3]
            i += s
        }
        return out
    }

    /**
     * Inverse of an affine matrix. Throws on a singular input — callers must
     * only construct transforms with non-zero determinant (the UI handles
     * forbid zero-area scales by clamping the drag delta).
     */
    fun invert(m: FloatArray): FloatArray {
        require(m.size == SIZE) { "matrix must have $SIZE values, got ${m.size}" }
        val a = m[0]; val b = m[1]; val tx = m[2]
        val c = m[3]; val d = m[4]; val ty = m[5]
        val det = a * d - b * c
        require(det != 0f) { "non-invertible affine matrix" }
        val invDet = 1f / det
        val ia = d * invDet
        val ib = -b * invDet
        val ic = -c * invDet
        val id = a * invDet
        val itx = (b * ty - d * tx) * invDet
        val ity = (c * tx - a * ty) * invDet
        return floatArrayOf(
            ia, ib, itx,
            ic, id, ity,
            0f, 0f, 1f,
        )
    }

    /** `result = a * b` (standard matrix product — apply `b` then `a`). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == SIZE && b.size == SIZE)
        val out = FloatArray(SIZE)
        out[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6]
        out[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7]
        out[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8]
        out[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6]
        out[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7]
        out[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8]
        out[6] = a[6] * b[0] + a[7] * b[3] + a[8] * b[6]
        out[7] = a[6] * b[1] + a[7] * b[4] + a[8] * b[7]
        out[8] = a[6] * b[2] + a[7] * b[5] + a[8] * b[8]
        return out
    }

    fun translation(tx: Float, ty: Float): FloatArray = floatArrayOf(
        1f, 0f, tx,
        0f, 1f, ty,
        0f, 0f, 1f,
    )

    /** Scale (sx, sy) around the point (ax, ay). */
    fun scaleAround(sx: Float, sy: Float, ax: Float, ay: Float): FloatArray = floatArrayOf(
        sx, 0f, ax * (1f - sx),
        0f, sy, ay * (1f - sy),
        0f, 0f, 1f,
    )

    /** Rotate by [radians] around the point (ax, ay). */
    fun rotationAround(radians: Float, ax: Float, ay: Float): FloatArray {
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        return floatArrayOf(
            cos, -sin, ax - cos * ax + sin * ay,
            sin, cos, ay - sin * ax - cos * ay,
            0f, 0f, 1f,
        )
    }
}
