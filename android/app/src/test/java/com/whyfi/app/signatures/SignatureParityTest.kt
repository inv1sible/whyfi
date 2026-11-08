package com.whyfi.app.signatures

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's matcher labels exactly like the backend's, on the shared
 * fixture (backend: scans/test_signature_parity.py, regenerate with
 * `manage.py generate_signature_parity`). Also pins the bundled catalog to
 * the one the fixture was made from.
 */
class SignatureParityTest {
    private val assetFile = File("src/main/assets/${SignatureCatalog.ASSET}")
    private val fixture: JsonObject = javaClass.classLoader!!.getResourceAsStream("signature_parity_cases.json")!!
        .reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    private val catalog = assetFile.reader(Charsets.UTF_8).use { SignatureCatalog.read(it) }

    private fun strings(o: JsonObject, key: String) = o.getAsJsonArray(key).map { it.asString }

    private fun expected(case: JsonObject) = case.getAsJsonArray("expect").map {
        val m = it.asJsonObject
        SignatureMatch(m.get("id").asString, m.get("rule").asString, m.get("via")?.asString)
    }

    @Test
    fun bundledCatalogIsTheFixturesCatalog() {
        val sha = MessageDigest.getInstance("SHA-256").digest(assetFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(fixture.get("catalog_sha256").asString, sha)
        assertEquals(fixture.get("catalog_version").asInt, catalog.version)
    }

    @Test
    fun bleMatchesTheBackend() {
        val cases = fixture.getAsJsonArray("ble")
        assertTrue(cases.size() > 500)
        val wrong = cases.mapNotNull { element ->
            val case = element.asJsonObject
            val a = case.getAsJsonObject("advert")
            val advert = BleMatcher.parseAdvert(
                a.get("mac").asString, a.get("name").asString, strings(a, "service_uuids"),
                strings(a, "manufacturer_records"), strings(a, "service_data"),
            )
            val got = BleMatcher.match(advert, catalog)
            if (got == expected(case)) null else "$a\n  want ${expected(case)}\n  got  $got"
        }
        assertTrue("${wrong.size} of ${cases.size()} differ:\n" + wrong.take(5).joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun wifiMatchesTheBackend() {
        val cases = fixture.getAsJsonArray("wifi")
        assertTrue(cases.size() > 500)
        val wrong = cases.mapNotNull { element ->
            val case = element.asJsonObject
            val ap = case.getAsJsonObject("ap")
            val got = WifiMatcher.match(WifiMatcher.parse(ap.get("bssid").asString, ap.get("ssid").asString, strings(ap, "vendor_ie_ouis")), catalog)
            if (got == expected(case)) null else "$ap\n  want ${expected(case)}\n  got  $got"
        }
        assertTrue("${wrong.size} of ${cases.size()} differ:\n" + wrong.take(5).joinToString("\n"), wrong.isEmpty())
    }

    /** Optional: real adverts exported from a backend into build/parity-real.json
     * (never committed: it's scan data). Skipped when absent. */
    @Test
    fun realDataMatchesTheBackendIfExported() {
        val file = File("build/parity-real.json").takeIf { it.isFile } ?: return
        val data = file.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        var checked = 0
        val wrong = ArrayList<String>()
        data.getAsJsonArray("ble").forEach { element ->
            val case = element.asJsonObject
            val a = case.getAsJsonObject("advert")
            val got = BleMatcher.match(BleMatcher.parseAdvert(a.get("mac").asString, a.get("name").asString, strings(a, "service_uuids"),
                strings(a, "manufacturer_records"), strings(a, "service_data")), catalog)
            checked++
            if (got != expected(case)) wrong += "$a want ${expected(case)} got $got"
        }
        data.getAsJsonArray("wifi").forEach { element ->
            val case = element.asJsonObject
            val ap = case.getAsJsonObject("ap")
            val got = WifiMatcher.match(WifiMatcher.parse(ap.get("bssid").asString, ap.get("ssid").asString, strings(ap, "vendor_ie_ouis")), catalog)
            checked++
            if (got != expected(case)) wrong += "$ap want ${expected(case)} got $got"
        }
        println("real-data parity: $checked adverts checked, ${wrong.size} differ")
        assertTrue("${wrong.size} of $checked differ:\n" + wrong.take(5).joinToString("\n"), wrong.isEmpty())
    }
}
