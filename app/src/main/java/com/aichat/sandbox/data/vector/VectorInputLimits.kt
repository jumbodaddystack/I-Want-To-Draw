package com.aichat.sandbox.data.vector

/**
 * Hard input limits for the Vector Art Tune-Up workflow (Phase 11).
 *
 * Centralizes the byte/char ceilings so the file reader, paste field, and large
 * input guard all agree on what "too big to import" means. These are deliberately
 * generous — large vectors are still allowed in, just guarded — and only block at
 * the point where reading or holding the text in memory becomes unsafe.
 */
object VectorInputLimits {

    /** Maximum size of a file (XML / SVG / bundle JSON) read from a content URI. */
    const val MAX_IMPORT_BYTES: Long = 5L * 1024L * 1024L

    /** Maximum number of characters accepted from a paste or file import. */
    const val MAX_PASTE_CHARS: Int = 5 * 1024 * 1024
}
