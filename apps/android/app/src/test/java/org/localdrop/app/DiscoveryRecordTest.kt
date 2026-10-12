package org.localdrop.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class DiscoveryRecordTest {
    private val txt = mapOf(
        "txtvers" to "1", "pv" to "1", "id" to "0123456789abcdef0123456789abcdef",
        "name" to "Pixel Phone", "type" to "android", "caps" to ""
    )

    @Test fun acceptsSharedVector() {
        val vectorText = javaClass.classLoader!!.getResource("discovery.json")!!.readText()
        val vector = JSONObject(vectorText)
        assertEquals("_localdrop._tcp.local.", vector.getString("serviceType"))
        val valid = vector.getJSONObject("valid")
        val properties = valid.keys().asSequence().associateWith { valid.getString(it) }
        assertEquals("Pixel Phone", DiscoveryRecord.parse(properties, 12345)?.name)
        assertEquals("file1", DiscoveryRecord.parse(properties + ("caps" to "file1"), 12345)?.capabilities)
    }

    @Test fun rejectsInvalidVersionAndIdentity() {
        assertNull(DiscoveryRecord.parse(txt + ("pv" to "2"), 12345))
        assertNull(DiscoveryRecord.parse(txt + ("id" to "bad"), 12345))
        assertNull(DiscoveryRecord.parse(txt + ("name" to "bad\nname"), 12345))
    }

    @Test fun identityIsRandomAndWellFormed() {
        val first = DiscoveryRecord.newId()
        assertTrue(first.matches(Regex("^[0-9a-f]{32}$")))
        assertNotEquals(first, DiscoveryRecord.newId())
    }
}
