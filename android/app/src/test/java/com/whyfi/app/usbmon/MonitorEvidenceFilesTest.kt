package com.whyfi.app.usbmon

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class MonitorEvidenceFilesTest {
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
