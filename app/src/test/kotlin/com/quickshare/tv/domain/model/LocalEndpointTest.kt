package com.quickshare.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalEndpointTest {

    @Test
    fun `endpointIdString returns the 4 ASCII bytes verbatim`() {
        val e = LocalEndpoint(
            endpointId   = "aZ09".toByteArray(Charsets.US_ASCII),
            endpointName = "TV",
            endpointInfo = byteArrayOf(),
        )
        assertEquals("aZ09", e.endpointIdString())
    }

    @Test
    fun `equals and hashCode use byte-array contents not identity`() {
        val a = LocalEndpoint(
            endpointId = byteArrayOf(1, 2, 3, 4),
            endpointName = "TV",
            endpointInfo = byteArrayOf(0x06, 0x10),
        )
        val b = LocalEndpoint(
            endpointId = byteArrayOf(1, 2, 3, 4),
            endpointName = "TV",
            endpointInfo = byteArrayOf(0x06, 0x10),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
