package com.aichat.sandbox.data.vector

/**
 * Phase 5 (sub-feature 2) — a real fill model for paths.
 *
 * Until now a path's fill was a single scalar color ([VectorStyle.fillColor] +
 * [VectorStyle.fillAlpha]) and any gradient in the source was parsed-but-dropped
 * with a warning. [VectorFill] carries solid **or** gradient fills end-to-end
 * (parsers → model → writers → preview).
 *
 * Wiring contract (the regression-safety rule): [VectorStyle.fill] is additive and
 * nullable. When it is null the existing scalar `fillColor`/`fillAlpha` path is
 * untouched, so every current document and every existing writer/preview test
 * stays byte-identical. A non-null [fill] **overrides** the scalar fill.
 *
 * Gradient coordinates are expressed in **viewport (user-space) units** — the same
 * coordinate space as the path data — so both writers emit them without any
 * bounding-box ambiguity (Android `<gradient>` and SVG `gradientUnits="userSpaceOnUse"`).
 *
 * Pure — no Android imports, so it stays JVM-testable like the rest of the model.
 */
sealed interface VectorFill {

    /** A flat color. Equivalent to the scalar `fillColor`/`fillAlpha` pair. */
    data class Solid(
        val color: String,
        val alpha: Float? = null,
    ) : VectorFill

    /** A linear gradient from (x1,y1) to (x2,y2) in viewport units. */
    data class Linear(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val stops: List<GradientStop>,
        val tileMode: String? = null,
    ) : VectorFill

    /** A radial gradient centered at (cx,cy) with the given radius in viewport units. */
    data class Radial(
        val cx: Float, val cy: Float,
        val radius: Float,
        val stops: List<GradientStop>,
        val tileMode: String? = null,
    ) : VectorFill

    /** A sweep (angular) gradient around (cx,cy). Has no SVG primitive. */
    data class Sweep(
        val cx: Float, val cy: Float,
        val stops: List<GradientStop>,
    ) : VectorFill
}

/** A single gradient color stop. [offset] is 0..1 along the gradient; [color] is `#AARRGGBB`/`#RRGGBB`. */
data class GradientStop(
    val offset: Float,
    val color: String,
)
