package com.whyfi.app.usbmon

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class MonitorEvidenceFilesTest {
    @Test fun everyExportHasMissionDateAndDistinctIdentityWithoutChangingEvidenceName() {
        fun mission(id: String) = MonitorMission.Stored(File(id), File(id, "report.pdf"), File(id, "mission.jsonl"),
            File(id, "capture.pcap"), false, "Survey", 0L)
        val first = mission("11111111-1111-4111-8111-111111111111")
        val second = mission("22222222-2222-4222-8222-222222222222")
        listOf(first.report, first.log, first.pcap!!).forEach { file ->
            val name = MonitorEvidenceFiles.exportName(first, file)
            assertTrue(name.startsWith("whyfi-19700101-000000Z-11111111-"))
            assertTrue(name.endsWith(file.name))
            assertNotEquals(name, MonitorEvidenceFiles.exportName(second, file))
        }
        assertEquals("capture.pcap", first.pcap!!.name)
        assertEquals("mission.jsonl", first.log.name)
        assertEquals("report.pdf", first.report.name)
    }

    @Test fun exportAuthorityMatchesRegisteredProviderAndRestrictedPaths() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val providers = manifest.getElementsByTagName("provider")
        val provider = (0 until providers.length).map { providers.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("android:name") == "androidx.core.content.FileProvider" }
        assertEquals("\${applicationId}" + MonitorEvidenceFiles.AUTHORITY_SUFFIX, provider.getAttribute("android:authorities"))
        assertEquals("false", provider.getAttribute("android:exported"))
        assertEquals("true", provider.getAttribute("android:grantUriPermissions"))
        val meta = provider.getElementsByTagName("meta-data").item(0) as org.w3c.dom.Element
        assertEquals("android.support.FILE_PROVIDER_PATHS", meta.getAttribute("android:name"))
        assertEquals("@xml/monitor_file_paths", meta.getAttribute("android:resource"))
        val paths = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/monitor_file_paths.xml"))
        assertEquals("monitor-missions/", (paths.getElementsByTagName("external-files-path")
            .item(0) as org.w3c.dom.Element).getAttribute("path"))
    }
}
