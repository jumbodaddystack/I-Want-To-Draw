package com.aichat.sandbox.data.vector

/**
 * A deterministic, non-fatal health assessment of a vector input (Phase 11).
 *
 * @property isSafe False only when [severity] is [Severity.UNSAFE]; expensive
 *   operations should be blocked when this is false.
 * @property severity How heavy the input is, from [Severity.OK] to [Severity.UNSAFE].
 * @property notes Short, user-facing lines explaining what drove the rating
 *   (e.g. "≈ 2.4 MB of XML", "11,200 paths"). Never a stack trace.
 */
data class VectorInputHealth(
    val isSafe: Boolean,
    val severity: Severity,
    val notes: List<String>,
) {
    enum class Severity {
        OK,
        LARGE,
        EXTREME,
        UNSAFE,
    }

    /** A one-line recommendation matching [severity], suitable for the health panel. */
    val recommendation: String
        get() = when (severity) {
            Severity.OK -> "This vector is a comfortable size for every tool."
            Severity.LARGE -> "This vector is on the large side; edits may take a moment."
            Severity.EXTREME ->
                "This vector is very large. Run a local Optimize first; AI is disabled until you opt in."
            Severity.UNSAFE ->
                "This vector is too large to work with safely. Optimize it locally or import a smaller file."
        }
}

/**
 * Classifies a vector input as OK / LARGE / EXTREME / UNSAFE so the workspace can
 * warn before — or block — expensive work on giant or pathological files.
 *
 * Two entry points:
 * - [assessInputText] works on raw pasted/imported text before it is parsed
 *   (byte size only), so it is cheap and always available.
 * - [assessMetrics] works on already-computed [VectorMetrics] (size + path/command
 *   counts), giving a sharper signal once a vector has been parsed.
 *
 * Pure and side-effect free; never throws.
 */
object VectorLargeInputGuard {

    /** A known-good health value, used as the default before anything is typed. */
    val SAFE: VectorInputHealth = VectorInputHealth(true, VectorInputHealth.Severity.OK, emptyList())

    // ---- byte thresholds (raw text size) ----
    private const val BYTES_LARGE = 500L * 1024L          // > 500 KB
    private const val BYTES_EXTREME = 2L * 1024L * 1024L  // > 2 MB
    private const val BYTES_UNSAFE = 5L * 1024L * 1024L   // > 5 MB

    // ---- command-count thresholds ----
    private const val COMMANDS_LARGE = 25_000
    private const val COMMANDS_EXTREME = 100_000
    private const val COMMANDS_UNSAFE = 250_000

    // ---- path-count thresholds ----
    private const val PATHS_LARGE = 500
    private const val PATHS_EXTREME = 2_000
    private const val PATHS_UNSAFE = 10_000

    fun assessInputText(input: String): VectorInputHealth {
        if (input.isBlank()) return SAFE
        val bytes = input.toByteArray(Charsets.UTF_8).size.toLong()
        val severity = severityForBytes(bytes)
        return build(severity, listOf("≈ ${formatBytes(bytes)} of input text"))
    }

    fun assessMetrics(metrics: VectorMetrics): VectorInputHealth {
        val bytesSeverity = severityForBytes(metrics.xmlBytes.toLong())
        val commandSeverity = severityForCommands(metrics.commandCount)
        val pathSeverity = severityForPaths(metrics.pathCount)
        val severity = maxOf(bytesSeverity, commandSeverity, pathSeverity)
        val notes = listOf(
            "≈ ${formatBytes(metrics.xmlBytes.toLong())} of XML",
            "${formatCount(metrics.pathCount)} path(s)",
            "${formatCount(metrics.commandCount)} command(s)",
        )
        return build(severity, notes)
    }

    private fun build(severity: VectorInputHealth.Severity, notes: List<String>) =
        VectorInputHealth(
            isSafe = severity != VectorInputHealth.Severity.UNSAFE,
            severity = severity,
            notes = notes,
        )

    private fun severityForBytes(bytes: Long): VectorInputHealth.Severity = when {
        bytes > BYTES_UNSAFE -> VectorInputHealth.Severity.UNSAFE
        bytes > BYTES_EXTREME -> VectorInputHealth.Severity.EXTREME
        bytes > BYTES_LARGE -> VectorInputHealth.Severity.LARGE
        else -> VectorInputHealth.Severity.OK
    }

    private fun severityForCommands(commands: Int): VectorInputHealth.Severity = when {
        commands > COMMANDS_UNSAFE -> VectorInputHealth.Severity.UNSAFE
        commands > COMMANDS_EXTREME -> VectorInputHealth.Severity.EXTREME
        commands > COMMANDS_LARGE -> VectorInputHealth.Severity.LARGE
        else -> VectorInputHealth.Severity.OK
    }

    private fun severityForPaths(paths: Int): VectorInputHealth.Severity = when {
        paths > PATHS_UNSAFE -> VectorInputHealth.Severity.UNSAFE
        paths > PATHS_EXTREME -> VectorInputHealth.Severity.EXTREME
        paths > PATHS_LARGE -> VectorInputHealth.Severity.LARGE
        else -> VectorInputHealth.Severity.OK
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatCount(count: Int): String =
        if (count >= 1000) "%,d".format(count) else count.toString()
}
