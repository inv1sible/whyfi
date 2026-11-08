package com.whyfi.app.signatures

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader
import java.util.Locale
import java.util.regex.Pattern

/**
 * Fieldwatch's signature catalog (MIT, see NOTICE), bundled as
 * `assets/fieldwatch-signatures-v2.json`, byte-identical to the backend's
 * `backend/scans/signatures/` copy. This package is a line-for-line port of
 * the backend matcher (`scans/ble_signatures.py`, `scans/wifi_signatures.py`),
 * itself a port of Fieldwatch's SignatureEngine.kt, so a standalone phone
 * labels exactly like the server. Parity is pinned by the shared fixture
 * `signature_parity_cases.json` (SignatureParityTest here,
 * test_signature_parity.py on the backend): change one matcher, change both.
 *
 * Labels are pattern matches, informational only: no alert state.
 */
class SignatureCatalog(val version: Int, val fleets: List<Fleet>) {
    data class Rule(val kind: String, val text: String, val companyId: Int, val dataPrefixHex: String, val radio: String?)

    data class Fleet(
        val id: String,
        val name: String,
        val kind: String,
        val matchAny: Boolean,
        val rules: List<Rule>,
        val clustered: Boolean,
    )

    private val byId = fleets.associateBy { it.id }
    fun get(id: String): Fleet? = byId[id]

    internal val ble by lazy { BleMatcher.Compiled(this) }
    internal val wifi by lazy { WifiMatcher.Compiled(this) }

    companion object {
        const val FORMAT = "fieldwatch-signatures"
        const val FORMAT_VERSION = 1
        const val ASSET = "fieldwatch-signatures-v2.json"

        fun read(reader: Reader): SignatureCatalog = parse(JsonParser.parseReader(reader).asJsonObject)

        fun parse(data: JsonObject): SignatureCatalog {
            val format = data.str("format")
            val formatVersion = data.get("formatVersion")?.takeIf { it.isJsonPrimitive }?.asInt
            require(format == FORMAT && formatVersion == FORMAT_VERSION) {
                "Not a $FORMAT v$FORMAT_VERSION catalog: format=$format formatVersion=$formatVersion"
            }
            val fleets = data.getAsJsonArray("fleets")?.mapNotNull { element ->
                val raw = element.asJsonObject
                if (!raw.bool("enabled", true)) return@mapNotNull null
                val rules = raw.getAsJsonArray("rules")?.mapNotNull { r ->
                    val rule = r.asJsonObject
                    if (!rule.bool("enabled", true)) null else Rule(
                        kind = rule.str("kind").orEmpty(),
                        text = rule.str("text").orEmpty(),
                        companyId = rule.int("companyId"),
                        dataPrefixHex = rule.str("dataPrefixHex").orEmpty(),
                        radio = rule.str("radio")?.ifEmpty { null },
                    )
                }.orEmpty()
                val id = raw.str("id") ?: return@mapNotNull null
                Fleet(
                    id = id,
                    name = raw.str("name")?.ifEmpty { null } ?: id,
                    kind = raw.str("kind")?.ifEmpty { null } ?: "OTHER",
                    matchAny = raw.bool("matchAny", true),
                    rules = rules,
                    clustered = raw.int("minPeers") > 0 || raw.bool("clusterByOui", false) || raw.bool("sequentialMac", false),
                )
            }.orEmpty()
            return SignatureCatalog(data.int("catalogVersion"), fleets)
        }

        private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }
        private fun JsonObject.str(key: String): String? = value(key)?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
        private fun JsonObject.int(key: String): Int = value(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0
        /** Python truthiness for JSON values: false, 0, "" and null are false. */
        private fun JsonObject.bool(key: String, default: Boolean): Boolean {
            val v = get(key) ?: return default
            if (v.isJsonNull) return false
            val p = v.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return true
            return when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble != 0.0
                else -> p.asString.isNotEmpty()
            }
        }
    }
}

/** Shared helpers, as in ble_signatures.py. */
internal object Sig {
    const val SIG_BASE_SUFFIX = "00001000800000805F9B34FB"
    private val HEX = Regex("[0-9A-F]*")

    /** Fieldwatch's hexOnly: letters and digits only, uppercased. */
    fun hexOnly(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)

    /** Every spelling a UUID can match under (Fieldwatch's uuidAliases). */
    fun uuidAliases(raw: String): Set<String> {
        val hex = hexOnly(raw)
        val out = linkedSetOf(raw.uppercase(Locale.ROOT), hex)
        if (hex.length == 4) {
            out += "0x$hex"
            out += "0000$hex-0000-1000-8000-00805F9B34FB"
            out += "0000$hex$SIG_BASE_SUFFIX"
            return out
        }
        if (hex.length == 32 && hex.startsWith("0000") && hex.endsWith(SIG_BASE_SUFFIX)) {
            val short = hex.substring(4, 8)
            out += short
            out += "0x$short"
            out += "0000$short-0000-1000-8000-00805F9B34FB"
        }
        return out
    }

    fun dataHex(raw: String): String? = hexOnly(raw).takeIf { HEX.matches(it) }

    /** Python's `entry.partition(":")`, head stripped. */
    fun splitEntry(entry: String): Pair<String, String>? {
        val i = entry.indexOf(':')
        if (i < 0) return null
        return entry.substring(0, i).trim() to entry.substring(i + 1)
    }

    /** `*` any run, `?` one char, case-insensitive, whole-string (Python re.fullmatch). */
    fun glob(pattern: String): Pattern {
        val body = buildString {
            pattern.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Pattern.quote(ch.toString()))
                }
            }
        }
        // UNIX_LINES: like Python, only \n stops `.`.
        return Pattern.compile(body, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNIX_LINES)
    }

    fun lower(s: String) = s.lowercase(Locale.ROOT)
}

/** One catalog hit: fleet id, the kind of the first rule that hit, and for
 * Wi-Fi address hits how (bssid / bssid_universal / vendor_ie). */
data class SignatureMatch(val id: String, val rule: String, val via: String? = null)
