package com.whyfi.app.lan

import org.junit.Assert.assertEquals
import org.junit.Test

class LanWebLinksTest {
    @Test
    fun webPortsBecomeBrowserLinks() {
        val links = LanWebLinks.links("192.168.1.1", listOf(22, 8080, 443, 80, 53, 32400))
        assertEquals(
            listOf("http://192.168.1.1", "https://192.168.1.1", "http://192.168.1.1:8080", "http://192.168.1.1:32400/web"),
            links.map { it.url },
        )
        assertEquals("https :443", links[1].label)
    }

    @Test
    fun hostsWithoutWebPortsHaveNoLinks() {
        assertEquals(emptyList<LanWebLinks.Link>(), LanWebLinks.links("10.0.0.2", listOf(22, 445, 9100)))
    }
}
