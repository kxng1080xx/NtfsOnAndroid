package dev.ntfsfm.core.transport

import dev.ntfsfm.core.transport.scsi.ReadCapacityResult
import dev.ntfsfm.core.transport.scsi.ScsiCommands
import dev.ntfsfm.core.transport.scsi.SenseData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScsiCommandsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun cdbEncoding() {
        assertArrayEquals(bytes(0x28, 0, 0x12, 0x34, 0x56, 0x78, 0, 0x01, 0x02, 0), ScsiCommands.read10(0x12345678, 0x0102))
        val w16 = ScsiCommands.write16(0x1_0000_0002L, 8)
        assertArrayEquals(bytes(0x8A, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 8, 0, 0), w16)
    }

    @Test fun capacityParsing() {
        val c10 = ReadCapacityResult.parse10(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0x02, 0))
        assertTrue(c10.isMaxSentinel)
        assertEquals(512, c10.blockSize)
        val c16 = ReadCapacityResult.parse16(bytes(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x10, 0) + ByteArray(20))
        assertEquals(0x1_0000_0000L, c16.lastLba)
        assertEquals(4096, c16.blockSize)
    }

    @Test fun senseParsing() {
        val sense = SenseData.parse(bytes(0x70, 0, 0x06, 0, 0, 0, 0, 10, 0, 0, 0, 0, 0x28, 0x00))!!
        assertTrue(sense.isUnitAttention)
        assertEquals(0x28, sense.additionalSenseCode)
    }
}
