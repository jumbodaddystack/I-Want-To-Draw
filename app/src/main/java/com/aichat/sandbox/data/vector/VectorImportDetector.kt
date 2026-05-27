package com.aichat.sandbox.data.vector

/** The vector source format detected for a block of pasted/imported text. */
enum class VectorImportFormat {
    ANDROID_VECTOR,
    SVG,
    PROJECT_BUNDLE,
    UNKNOWN,
}

/**
 * Sniffs whether a block of text is Android `VectorDrawable` XML or SVG so the
 * Phase 9 import path can route it to the right parser.
 *
 * Detection is deliberately shallow and crash-proof: it strips a leading BOM,
 * whitespace, an XML declaration, and any leading comments, then inspects the
 * first real element tag. It never parses the document and never throws.
 */
object VectorImportDetector {

    /** Whitespace-tolerant marker for a Phase 9/10 portable project bundle JSON. */
    private val BUNDLE_KIND = Regex("\"kind\"\\s*:\\s*\"vector_tuneup_project\"")

    fun detect(input: String): VectorImportFormat {
        // A portable project bundle is JSON, not XML — sniff it before tag parsing.
        val trimmed = input.removePrefix("﻿").trimStart()
        if (trimmed.startsWith("{") && BUNDLE_KIND.containsMatchIn(trimmed)) {
            return VectorImportFormat.PROJECT_BUNDLE
        }
        val root = firstElementName(input) ?: return VectorImportFormat.UNKNOWN
        return when (root) {
            "vector" -> VectorImportFormat.ANDROID_VECTOR
            "svg" -> VectorImportFormat.SVG
            else -> VectorImportFormat.UNKNOWN
        }
    }

    /**
     * Returns the lowercased local name of the first start tag, skipping a BOM,
     * leading whitespace, the `<?xml …?>` declaration, `<!-- … -->` comments, and
     * `<!DOCTYPE …>`/other `<! …>` declarations. Returns null when no usable
     * start tag is found.
     */
    private fun firstElementName(input: String): String? {
        val s = input.removePrefix("﻿")
        val n = s.length
        var i = 0
        while (i < n) {
            // Skip whitespace between markup.
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != '<') return null

            when {
                s.startsWith("<?", i) -> {
                    val end = s.indexOf("?>", i + 2)
                    if (end < 0) return null
                    i = end + 2
                }
                s.startsWith("<!--", i) -> {
                    val end = s.indexOf("-->", i + 4)
                    if (end < 0) return null
                    i = end + 3
                }
                s.startsWith("<!", i) -> {
                    val end = s.indexOf('>', i + 2)
                    if (end < 0) return null
                    i = end + 1
                }
                else -> {
                    // A real start tag: read the tag name up to whitespace/>//.
                    var j = i + 1
                    if (j < n && (s[j] == '/' )) return null
                    val start = j
                    while (j < n && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                    if (j == start) return null
                    val raw = s.substring(start, j)
                    // Drop any namespace prefix (e.g. "svg:svg").
                    val local = raw.substringAfterLast(':')
                    return local.lowercase()
                }
            }
        }
        return null
    }
}
