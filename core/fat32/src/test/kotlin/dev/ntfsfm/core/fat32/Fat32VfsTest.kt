package dev.ntfsfm.core.fat32

import dev.ntfsfm.core.block.FileBackedBlockDevice
import dev.ntfsfm.core.vfs.VfsException
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Fat32VfsTest {
    /** Minimal mkfs.fat: 512-byte sectors, 1 sector per cluster so directories grow quickly. */
    private suspend fun blankVolume(): Fat32Vfs {
        val totalSectors = 8192L
        val fatSize = 64L
        val image = Files.createTempFile("fat32", ".img").also { it.toFile().deleteOnExit() }
        val device = FileBackedBlockDevice.createBlank(image, totalSectors)
        val boot = ByteArray(512)
        boot.writeU16le(11, 512)
        boot[13] = 1
        boot.writeU16le(14, 32)
        boot[16] = 2
        boot.writeU32le(32, totalSectors)
        boot.writeU32le(36, fatSize)
        boot.writeU32le(44, 2)
        boot.writeU16le(48, 1)
        "TESTVOL    ".toByteArray().copyInto(boot, 71)
        "FAT32   ".toByteArray().copyInto(boot, 82)
        boot.writeU16le(510, 0xAA55)
        device.writeSectors(0, 1, boot)
        val fsInfo = ByteArray(512)
        fsInfo.writeU32le(0, 0x41615252)
        fsInfo.writeU32le(484, 0x61417272)
        fsInfo.writeU32le(488, 0xFFFFFFFFL)
        device.writeSectors(1, 1, fsInfo)
        val fat = ByteArray(512)
        fat.writeU32le(0, 0x0FFFFFF8)
        fat.writeU32le(4, FAT32_EOC)
        fat.writeU32le(8, FAT32_EOC) // root directory
        device.writeSectors(32, 1, fat)
        device.writeSectors(32 + fatSize, 1, fat)
        return Fat32Vfs.mount(device, 0, "test", "Test")
    }

    @Test fun roundTrip() = runBlocking {
        val vfs = blankVolume()
        val freeBefore = vfs.spaceInfo().freeBytes

        vfs.createDirectory("Docs")
        val payload = ByteArray(3000) { (it * 7).toByte() }
        vfs.openOutputStream("Docs/A long file name.txt").use { it.write(payload) }
        assertArrayEquals(payload, vfs.openInputStream("docs/a LONG file NAME.TXT").use { it.readBytes() })
        assertEquals(3000L, vfs.stat("Docs/A long file name.txt").sizeBytes)

        // 40 long names need far more than one 16-slot cluster: the directory must grow.
        repeat(40) { i -> vfs.openOutputStream("Docs/Some rather long name number $i.bin").use { it.write(i) } }
        val names = vfs.listDirectory("Docs").toList().map { it.name }.toSet()
        assertEquals(41, names.size)
        assertTrue("Some rather long name number 39.bin" in names)
        assertEquals(39, vfs.openInputStream("Docs/Some rather long name number 39.bin").use { it.read() })

        // Overwrite truncates.
        vfs.openOutputStream("Docs/A long file name.txt").use { it.write(byteArrayOf(1, 2)) }
        assertEquals(2L, vfs.stat("Docs/A long file name.txt").sizeBytes)

        vfs.rename("Docs/A long file name.txt", "Renamed.txt")
        assertArrayEquals(byteArrayOf(1, 2), vfs.openInputStream("Docs/Renamed.txt").use { it.readBytes() })
        try {
            vfs.stat("Docs/A long file name.txt")
            fail("old name still resolves")
        } catch (_: VfsException.NotFound) {
        }

        vfs.delete("Docs")
        assertEquals(emptyList<String>(), vfs.listDirectory("").toList().map { it.name })
        assertEquals(freeBefore, vfs.spaceInfo().freeBytes)
    }

    @Test fun shortNames() {
        assertTrue(Fat32ShortName.fitsExactly("README.TXT"))
        assertTrue(!Fat32ShortName.fitsExactly("readme.txt"))
        assertEquals("MYDOCU~2.TXT", Fat32ShortName.generate("My Document.txt", setOf("MYDOCU~1.TXT")))
    }
}
