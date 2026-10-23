package com.whyfi.app.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnvifDiscoveryTest {
    private val id = "uuid:request-1"
    private val response = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"><s:Header><a:RelatesTo>$id</a:RelatesTo></s:Header><s:Body><d:ProbeMatches><d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:camera-1</a:Address></a:EndpointReference><d:Types>tds:Device</d:Types><d:Scopes>onvif://www.onvif.org/name/Front%20Door</d:Scopes><d:XAddrs>http://192.168.1.42/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"""

    @Test fun `matching probe response exposes identity and service address`() {
        val bytes = response.toByteArray()
        val match = OnvifDiscovery.parseResponse(bytes, bytes.size, id)!!
        assertEquals("urn:uuid:camera-1", match.endpoint)
        assertEquals("Front Door", match.name)
        assertEquals("http://192.168.1.42/onvif/device_service", match.xaddrs)
    }

    @Test fun `unrelated and entity-bearing responses are ignored`() {
        val bytes = response.toByteArray()
        assertNull(OnvifDiscovery.parseResponse(bytes, bytes.size, "uuid:other"))
        val unsafe = """<!DOCTYPE x [<!ENTITY attack SYSTEM "file:///etc/passwd">]>$response""".toByteArray()
        assertNull(OnvifDiscovery.parseResponse(unsafe, unsafe.size, id))
    }
}
