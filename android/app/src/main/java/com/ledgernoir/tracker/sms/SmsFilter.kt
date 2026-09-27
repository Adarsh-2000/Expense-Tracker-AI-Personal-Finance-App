package com.ledgernoir.tracker.sms

/**
 * Pure Kotlin — no Android imports. Safe to unit-test on the JVM.
 *
 * Indian DLT sender headers come in two shapes:
 *   2-part: <operator>-<entity>          e.g. "AX-AXISBK", "VM-IDFCFB", "VK-BOBTXN"
 *   3-part: <operator>-<entity>-<cat>     e.g. "AX-IDFCFB-S", "AD-IDFCFB-T", "JD-AXISBK-P"
 * The trailing 1-char category is S(service)/T(transactional)/P(promo)/G(govt).
 * normalizeSender extracts the stable ENTITY id by stripping the operator prefix
 * AND any trailing single-letter category suffix, so both shapes match the whitelist.
 */
class SmsFilter {

    // Exact bank entity ids (after normalization). Extend with observed variants;
    // exact-match only — look-alikes like "AXISBKX"/"BOBSPAM" are still rejected.
    private val whitelist = setOf(
        "AXISBK", "AXISBN",          // Axis Bank
        "IDFCFB", "IDFCBK",          // IDFC First Bank
        "BOBTXN", "BOBSMS", "BOBIBN" // Bank of Baroda
    )

    /**
     * Extracts the bank entity id from a DLT header. Uppercases, splits on '-',
     * drops the leading operator segment, and drops a trailing single-letter
     * category segment (S/T/P/G). Examples:
     *   "AX-AXISBK"   -> "AXISBK"
     *   "AX-IDFCFB-S" -> "IDFCFB"
     *   "AD-IDFCFB-T" -> "IDFCFB"
     *   "AXISBK"      -> "AXISBK"
     */
    fun normalizeSender(raw: String): String {
        val upper = raw.trim().uppercase()
        if (!upper.contains('-')) return upper
        val parts = upper.split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return upper
        // Drop a trailing single-letter category suffix (S/T/P/G) if present.
        var segs = parts
        if (segs.size >= 3 && segs.last().length == 1) {
            segs = segs.dropLast(1)
        }
        // The entity id is the last remaining segment after the operator prefix.
        return segs.last()
    }

    /**
     * Returns true only when the normalized sender is EXACTLY one of the
     * enumerated whitelisted bank IDs.
     */
    fun isWhitelisted(sender: String): Boolean {
        if (sender.isBlank()) return false
        return normalizeSender(sender) in whitelist
    }
}
