package com.whyfi.app.diagnostics

/**
 * Turns what Android's ApplicationExitInfo hands back into crash-log text.
 * Pure (no Android types) so it runs in the JVM test source set; the
 * Android-facing part is ExitReasonRecorder.
 */
object ExitReportFormatter {

    /** Caps one entry so a huge ANR dump can't bloat the crash log or the
     * upload — the top of the trace is the part that matters. */
    const val MAX_TRACE_CHARS = 16_000

    /**
     * A native crash's trace is a binary Tombstone protobuf (API 31+), not
     * text, and parsing it properly needs Android's tombstone.proto schema.
     * The readable parts — signal name, abort message, library paths and
     * symbol names of each backtrace frame — are stored as plain UTF-8
     * strings inside it, so pulling out printable runs (what `strings` does)
     * recovers what a human needs to see which code crashed.
     */
    fun printableStrings(bytes: ByteArray, minLength: Int = 4, maxChars: Int = MAX_TRACE_CHARS): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= minLength && out.length < maxChars) {
                out.append(run).append('\n')
            }
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        return out.toString().take(maxChars).trimEnd()
    }

    /** The body of one crash-log entry for an exit our own handler missed. */
    fun format(
        kind: String,
        description: String?,
        processName: String?,
        trace: String?,
        breadcrumbs: String?,
    ): String = buildString {
        append("$kind (recorded by Android, not by whyfi's own handler)\n")
        if (!description.isNullOrBlank()) append("Description: $description\n")
        if (!processName.isNullOrBlank()) append("Process: $processName\n")
        if (!trace.isNullOrBlank()) append("\n").append(trace.take(MAX_TRACE_CHARS)).append('\n')
        if (!breadcrumbs.isNullOrBlank()) append("\nLast diagnostic steps:\n").append(breadcrumbs).append('\n')
    }.trimEnd()
}
