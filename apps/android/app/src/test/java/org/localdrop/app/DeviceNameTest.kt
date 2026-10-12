package org.localdrop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceNameTest {
    @Test fun trimsAndBoundsUtf8Name() {
        assertEquals("Pixel Phone", DeviceName.normalize("  Pixel Phone  "))
        assertThrows(IllegalArgumentException::class.java) { DeviceName.normalize("bad\nname") }
        assertThrows(IllegalArgumentException::class.java) { DeviceName.normalize("é".repeat(33)) }
    }
}
