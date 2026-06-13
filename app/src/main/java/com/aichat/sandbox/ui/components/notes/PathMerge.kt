package com.aichat.sandbox.ui.components.notes

/**
 * Phase 17.5 (#3) — merge compatible path payloads into one multi-subpath
 * payload. Unlike a boolean op ([PathBooleanBridge]) this does **no** clipping:
 * it concatenates every input's subpaths under a single shared fill, which is
 * exactly what "tidy up these paths into one" wants. Representable since 16.1
 * gave the codec multi-subpath payloads with a fill rule, so even-odd inputs
 * keep their holes.
 *
 * JVM-pure (no android.graphics) so the merge rules are unit-testable.
 */
object PathMerge {

    /**
     * Two payloads merge into one shared-fill path only when their fill +
     * stroke styling already matches — otherwise concatenating them would
     * silently restyle geometry. Geometry (subpaths / fill rule's winding)
     * is free to differ; the fill *rule* must match so the combined winding
     * is well-defined.
     */
    fun compatible(a: PathCodec.PathPayload, b: PathCodec.PathPayload): Boolean =
        a.fillArgb == b.fillArgb &&
            a.fillRule == b.fillRule &&
            a.strokeStyle == b.strokeStyle &&
            a.capJoin == b.capJoin &&
            a.gradient == b.gradient

    /**
     * Merge [payloads]' subpaths into one payload, keeping the first's style.
     * Returns null when there are fewer than two payloads or any payload is
     * incompatible with the first — the caller leaves the selection untouched.
     */
    fun merge(payloads: List<PathCodec.PathPayload>): PathCodec.PathPayload? {
        if (payloads.size < 2) return null
        val first = payloads.first()
        if (payloads.any { !compatible(first, it) }) return null
        val subpaths = payloads.flatMap { it.subpaths }
        if (subpaths.isEmpty()) return null
        return first.copy(subpaths = subpaths)
    }

    /**
     * Partition [payloads] into maximal compatible runs **preserving order**,
     * so a one-tap "merge selection" can fold each style-group into its own
     * path instead of refusing a mixed selection. Singletons are returned as
     * one-element groups (the caller leaves those as-is).
     */
    fun groupByStyle(payloads: List<PathCodec.PathPayload>): List<List<Int>> {
        val groups = ArrayList<MutableList<Int>>()
        outer@ for (i in payloads.indices) {
            for (g in groups) {
                if (compatible(payloads[g.first()], payloads[i])) {
                    g.add(i)
                    continue@outer
                }
            }
            groups.add(mutableListOf(i))
        }
        return groups
    }
}
