package com.aichat.sandbox.data.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Phase **I7 — select-similar + snapping (N2, idea #8)**: the constraint / snap
 * engine. Given a set of related items (their world-space AABBs), detect the
 * near-regularities a human eye reads as "almost aligned / evenly spaced /
 * symmetric" and propose the small nudges that would make them exact.
 *
 * ## Pure, local, edit-ops-shaped
 * Detection is pure float geometry over bounding boxes — no ink, no model, fully
 * JVM-testable. Each proposed nudge is a **translation** `(dx, dy)` of one item,
 * which the caller expresses as an ordinary `EditOp.Transform` on the canonical
 * `StrokeCodec` / shape payload and surfaces through the *same* accept/decline
 * preview chips as AI edit suggestions (`EditPreviewController` →
 * `PendingEdit`). So a snap is just a locally-authored edit-op: `StrokeCodec`
 * stays canonical and the AI edit pipeline is untouched (Adoption principle 2).
 *
 * ## Why bounding boxes
 * Alignment / spacing / symmetry are layout relations between *items*, which the
 * AABB captures exactly and cheaply. (The richer per-stroke shape relations —
 * "these two strokes are the same mark" — are [StrokeSimilarity]'s job.) Working
 * on AABBs also means every `NoteItem` kind participates uniformly, so snapping a
 * row that mixes strokes, shapes and stickies behaves consistently.
 *
 * ## Conservative by design
 * The engine only *tidies* arrangements that are already nearly regular (within a
 * tolerance) and only emits a nudge when there is something to fix (a move larger
 * than [Config.minMove]). It will not reflow a deliberately irregular layout. When
 * several constraints would move the same item along the same axis, [resolve]
 * keeps the strongest and drops the rest, so the merged proposal is never
 * self-contradictory.
 */
object ConstraintSnap {

    /** An item to consider, identified by id with its world AABB. */
    class Item(val id: String, bounds: FloatArray) {
        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]
        val centerX = (bounds[0] + bounds[2]) * 0.5f
        val centerY = (bounds[1] + bounds[3]) * 0.5f
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        val diag = hypot(width, height)
    }

    enum class Axis { X, Y }

    enum class Kind(val axis: Axis) {
        ALIGN_LEFT(Axis.X),
        ALIGN_RIGHT(Axis.X),
        ALIGN_CENTER_X(Axis.X),
        ALIGN_TOP(Axis.Y),
        ALIGN_BOTTOM(Axis.Y),
        ALIGN_CENTER_Y(Axis.Y),
        DISTRIBUTE_X(Axis.X),
        DISTRIBUTE_Y(Axis.Y),
        SYMMETRY_X(Axis.X),
        SYMMETRY_Y(Axis.Y),
    }

    /** A single item's proposed translation (one axis component is ~0). */
    data class Adjustment(val id: String, val dx: Float, val dy: Float)

    /** A detected near-regularity and the nudges that would make it exact. */
    data class Constraint(
        val kind: Kind,
        val description: String,
        val adjustments: List<Adjustment>,
    ) {
        /** Stronger = more items participate. The primary [resolve] priority. */
        val strength: Int get() = adjustments.size

        /** Total travel the snap costs; the [resolve] tie-breaker (cheaper wins). */
        val totalMove: Float get() = adjustments.sumOf { hypot(it.dx, it.dy).toDouble() }.toFloat()
    }

    /**
     * @property alignTolerance max spread (world px) within which edges/centers
     *   are treated as "meant to be aligned".
     * @property spacingTolerance max gap deviation for a row to count as
     *   "meant to be evenly spaced".
     * @property symmetryTolerance max mirror discrepancy for a pair to count as
     *   "meant to be symmetric".
     * @property minMove smallest nudge worth proposing — below this the items are
     *   already effectively snapped, so no constraint is emitted.
     */
    data class Config(
        val alignTolerance: Float = 14f,
        val spacingTolerance: Float = 14f,
        val symmetryTolerance: Float = 16f,
        val minMove: Float = 0.5f,
    )

    /**
     * All near-regularities found among [items], strongest first (then cheapest).
     * May contain constraints that conflict on an axis; call [resolve] to merge
     * them into one consistent nudge set.
     */
    fun detect(items: List<Item>, config: Config = Config()): List<Constraint> {
        if (items.size < 2) return emptyList()
        val out = ArrayList<Constraint>()

        alignment(items, config, Kind.ALIGN_LEFT, "Align left edges") { it.minX }?.let(out::add)
        alignment(items, config, Kind.ALIGN_RIGHT, "Align right edges") { it.maxX }?.let(out::add)
        alignment(items, config, Kind.ALIGN_CENTER_X, "Align horizontal centers") { it.centerX }?.let(out::add)
        alignment(items, config, Kind.ALIGN_TOP, "Align top edges") { it.minY }?.let(out::add)
        alignment(items, config, Kind.ALIGN_BOTTOM, "Align bottom edges") { it.maxY }?.let(out::add)
        alignment(items, config, Kind.ALIGN_CENTER_Y, "Align vertical centers") { it.centerY }?.let(out::add)

        distribute(items, config, Axis.X)?.let(out::add)
        distribute(items, config, Axis.Y)?.let(out::add)

        symmetry(items, config, Axis.X)?.let(out::add)
        symmetry(items, config, Axis.Y)?.let(out::add)

        out.sortWith(compareByDescending<Constraint> { it.strength }.thenBy { it.totalMove })
        return out
    }

    /**
     * Merge [constraints] into one conflict-free nudge set: walk them strongest
     * first and, per item, accept at most one X-nudge and one Y-nudge (the first /
     * strongest wins on each axis). Output is ordered by [order] (the caller's
     * item order) for determinism, with sub-[Config.minMove] residuals dropped.
     */
    fun resolve(
        constraints: List<Constraint>,
        order: List<String>,
        config: Config = Config(),
    ): List<Adjustment> {
        val ordered = constraints.sortedWith(
            compareByDescending<Constraint> { it.strength }.thenBy { it.totalMove },
        )
        val dx = HashMap<String, Float>()
        val dy = HashMap<String, Float>()
        for (c in ordered) {
            val axisX = c.kind.axis == Axis.X
            for (adj in c.adjustments) {
                if (axisX) {
                    if (adj.id !in dx) dx[adj.id] = adj.dx
                } else {
                    if (adj.id !in dy) dy[adj.id] = adj.dy
                }
            }
        }
        val ids = LinkedHashSet<String>()
        for (id in order) if (id in dx || id in dy) ids.add(id)
        // Any ids not in `order` (defensive) appended in stable encounter order.
        for (id in dx.keys) ids.add(id)
        for (id in dy.keys) ids.add(id)

        val out = ArrayList<Adjustment>(ids.size)
        for (id in ids) {
            val mx = dx[id] ?: 0f
            val my = dy[id] ?: 0f
            if (abs(mx) < config.minMove && abs(my) < config.minMove) continue
            out.add(Adjustment(id, mx, my))
        }
        return out
    }

    // ── Alignment: snap the dominant near-equal cluster to its mean ───────────

    private inline fun alignment(
        items: List<Item>,
        config: Config,
        kind: Kind,
        description: String,
        value: (Item) -> Float,
    ): Constraint? {
        // Densest cluster: the seed whose tolerance window holds the most items
        // (ties → first seed in input order). Snap that cluster to its mean.
        var bestSeed = -1
        var bestCount = 0
        for (i in items.indices) {
            val v = value(items[i])
            var count = 0
            for (j in items.indices) if (abs(value(items[j]) - v) <= config.alignTolerance) count++
            if (count > bestCount) {
                bestCount = count
                bestSeed = i
            }
        }
        if (bestSeed < 0 || bestCount < 2) return null

        val seedVal = value(items[bestSeed])
        val members = items.filter { abs(value(it) - seedVal) <= config.alignTolerance }
        val target = members.map { value(it) }.average().toFloat()

        val adjustments = ArrayList<Adjustment>(members.size)
        var anySignificant = false
        for (m in members) {
            val delta = target - value(m)
            if (abs(delta) >= config.minMove) anySignificant = true
            if (kind.axis == Axis.X) adjustments.add(Adjustment(m.id, delta, 0f))
            else adjustments.add(Adjustment(m.id, 0f, delta))
        }
        if (!anySignificant) return null // already aligned — nothing to propose
        return Constraint(kind, description, adjustments.filter { abs(it.dx) >= config.minMove || abs(it.dy) >= config.minMove })
    }

    // ── Even spacing: redistribute a near-even row/column to equal gaps ────────

    private fun distribute(items: List<Item>, config: Config, axis: Axis): Constraint? {
        if (items.size < 3) return null
        val center: (Item) -> Float = if (axis == Axis.X) { it -> it.centerX } else { it -> it.centerY }
        val cross: (Item) -> Float = if (axis == Axis.X) { it -> it.centerY } else { it -> it.centerX }

        // Only distribute along the axis the items actually span (a horizontal row
        // for X, a vertical column for Y) — never reflow a stack sideways.
        val spanMain = items.maxOf(center) - items.minOf(center)
        val spanCross = items.maxOf(cross) - items.minOf(cross)
        if (spanMain <= config.minMove || spanMain < spanCross) return null

        val sorted = items.sortedBy(center)
        val n = sorted.size
        val first = center(sorted.first())
        val last = center(sorted.last())
        val meanGap = (last - first) / (n - 1)
        if (meanGap <= config.minMove) return null

        // Require the layout to already be roughly even (don't reflow on purpose).
        var maxDev = 0f
        for (i in 0 until n - 1) {
            val gap = center(sorted[i + 1]) - center(sorted[i])
            maxDev = max(maxDev, abs(gap - meanGap))
        }
        if (maxDev > config.spacingTolerance) return null

        val adjustments = ArrayList<Adjustment>(n)
        var anySignificant = false
        for (k in 1 until n - 1) { // ends stay fixed
            val targetCenter = first + k * meanGap
            val delta = targetCenter - center(sorted[k])
            if (abs(delta) < config.minMove) continue
            anySignificant = true
            if (axis == Axis.X) adjustments.add(Adjustment(sorted[k].id, delta, 0f))
            else adjustments.add(Adjustment(sorted[k].id, 0f, delta))
        }
        if (!anySignificant) return null
        val kind = if (axis == Axis.X) Kind.DISTRIBUTE_X else Kind.DISTRIBUTE_Y
        val desc = if (axis == Axis.X) "Even horizontal spacing" else "Even vertical spacing"
        return Constraint(kind, desc, adjustments)
    }

    // ── Symmetry: snap near-mirror pairs to exact symmetry about the mid-axis ──

    private fun symmetry(items: List<Item>, config: Config, axis: Axis): Constraint? {
        val center: (Item) -> Float = if (axis == Axis.X) { it -> it.centerX } else { it -> it.centerY }
        val cross: (Item) -> Float = if (axis == Axis.X) { it -> it.centerY } else { it -> it.centerX }
        val lo = items.minOf { if (axis == Axis.X) it.minX else it.minY }
        val hi = items.maxOf { if (axis == Axis.X) it.maxX else it.maxY }
        val mid = (lo + hi) * 0.5f

        val used = BooleanArray(items.size)
        val adjustments = ArrayList<Adjustment>()
        var anySignificant = false

        for (i in items.indices) {
            if (used[i]) continue
            val a = items[i]
            val partner = findMirror(items, used, i, mid, center, cross, config)
            if (partner >= 0) {
                if (pairSnap(items, used, i, partner, mid, center, axis, config, adjustments)) {
                    anySignificant = true
                }
                continue
            }
            // No partner: snap a near-axis item onto the axis (self-symmetric).
            val da = center(a) - mid
            if (abs(da) in config.minMove..config.symmetryTolerance) {
                adjustments.add(adj(axis, a.id, -da))
                anySignificant = true
            }
            used[i] = true
        }

        if (adjustments.size < 2 || !anySignificant) return null
        val kind = if (axis == Axis.X) Kind.SYMMETRY_X else Kind.SYMMETRY_Y
        val desc = if (axis == Axis.X) "Symmetrize left/right" else "Symmetrize top/bottom"
        return Constraint(kind, desc, adjustments)
    }

    /** Closest unused mirror partner of [i] across [mid], within tolerances. */
    private inline fun findMirror(
        items: List<Item>,
        used: BooleanArray,
        i: Int,
        mid: Float,
        center: (Item) -> Float,
        cross: (Item) -> Float,
        config: Config,
    ): Int {
        val a = items[i]
        val mirrored = 2f * mid - center(a)
        var best = -1
        var bestErr = Float.MAX_VALUE
        for (j in items.indices) {
            if (j == i || used[j]) continue
            val b = items[j]
            // Opposite sides (or both ~on-axis), matched cross position & size.
            if (sign(center(a) - mid) == sign(center(b) - mid) &&
                abs(center(a) - mid) > config.symmetryTolerance &&
                abs(center(b) - mid) > config.symmetryTolerance
            ) continue
            if (abs(cross(a) - cross(b)) > config.symmetryTolerance) continue
            if (sizeRatio(a, b) < 0.7f) continue
            val err = abs(center(b) - mirrored)
            if (err <= config.symmetryTolerance && err < bestErr) {
                bestErr = err
                best = j
            }
        }
        return best
    }

    /**
     * Snap the pair (i, j) so their midpoint lands on [mid]. Returns true when a
     * significant move was emitted, false when already symmetric, null never.
     */
    private fun pairSnap(
        items: List<Item>,
        used: BooleanArray,
        i: Int,
        j: Int,
        mid: Float,
        center: (Item) -> Float,
        axis: Axis,
        config: Config,
        out: ArrayList<Adjustment>,
    ): Boolean {
        val a = items[i]
        val b = items[j]
        used[i] = true
        used[j] = true
        // pair midpoint = mid + e/2 where e = da + db; nudge both by -e/2.
        val e = (center(a) - mid) + (center(b) - mid)
        val delta = -e * 0.5f
        if (abs(delta) < config.minMove) return false
        out.add(adj(axis, a.id, delta))
        out.add(adj(axis, b.id, delta))
        return true
    }

    private fun adj(axis: Axis, id: String, delta: Float): Adjustment =
        if (axis == Axis.X) Adjustment(id, delta, 0f) else Adjustment(id, 0f, delta)

    private fun sizeRatio(a: Item, b: Item): Float {
        val hi = max(a.diag, b.diag)
        if (hi <= 1e-4f) return 1f
        return min(a.diag, b.diag) / hi
    }
}
