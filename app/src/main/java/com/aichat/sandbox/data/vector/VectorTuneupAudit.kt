package com.aichat.sandbox.data.vector

/**
 * A user-facing health snapshot for one vector version or a whole project
 * (Phase 11). Drives `VectorProjectHealthPanel`.
 *
 * @property xmlBytes Canonical XML size of the assessed version.
 * @property pathCount Number of paths.
 * @property commandCount Total path commands.
 * @property warningCount Parser/validator warnings carried by the version.
 * @property health The OK/LARGE/EXTREME/UNSAFE rating + recommendation.
 */
data class VectorProjectHealth(
    val xmlBytes: Int,
    val pathCount: Int,
    val commandCount: Int,
    val warningCount: Int,
    val health: VectorInputHealth,
) {
    val severity: VectorInputHealth.Severity get() = health.severity
    val recommendation: String get() = health.recommendation
}

/**
 * Builds [VectorProjectHealth] snapshots from already-computed metrics.
 *
 * The audit is deterministic and pure — it just folds [VectorMetrics] through
 * [VectorLargeInputGuard]. [assessProject] rates a project by its heaviest
 * version so the Diagnostics panel always reflects the worst case the user would
 * hit (e.g. an unoptimized original that later versions branched from).
 */
object VectorTuneupAudit {

    fun assessMetrics(metrics: VectorMetrics): VectorProjectHealth =
        VectorProjectHealth(
            xmlBytes = metrics.xmlBytes,
            pathCount = metrics.pathCount,
            commandCount = metrics.commandCount,
            warningCount = metrics.warnings.size,
            health = VectorLargeInputGuard.assessMetrics(metrics),
        )

    /** Rates a project by its heaviest version (highest severity, then most bytes). */
    fun assessProject(versions: List<VectorMetrics>): VectorProjectHealth? {
        if (versions.isEmpty()) return null
        return versions
            .map { assessMetrics(it) }
            .maxWith(compareBy({ it.severity.ordinal }, { it.xmlBytes }))
    }
}
