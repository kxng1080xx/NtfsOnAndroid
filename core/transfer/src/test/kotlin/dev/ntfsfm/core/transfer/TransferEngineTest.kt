package dev.ntfsfm.core.transfer

import dev.ntfsfm.core.vfs.LocalVfs
import dev.ntfsfm.core.vfs.VolumeIdentity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferEngineTest {
    private fun volume(id: String): Pair<File, LocalVfs> {
        val dir = Files.createTempDirectory(id).toFile().also { it.deleteOnExit() }
        return dir to LocalVfs(dir, VolumeIdentity(id, id))
    }

    @Test fun copyThenKeepBothThenCrossVolumeMove() = runBlocking {
        val (srcDir, src) = volume("src")
        val (dstDir, dst) = volume("dst")
        File(srcDir, "Photos/2024").mkdirs()
        File(srcDir, "Photos/a.jpg").writeText("aaa")
        File(srcDir, "Photos/2024/b.jpg").writeText("bbbb")

        val engine = TransferEngine()
        fun run(mode: TransferMode, policy: ConflictPolicy) =
            engine.execute(src, listOf("Photos"), dst, "", mode, TransferController(), policy) { error("should not ask") }

        val copy = run(TransferMode.COPY, ConflictPolicy.ASK).toList()
        assertTrue(copy.last().isDone)
        assertEquals(7L, copy.last().bytesTransferred)
        assertEquals("bbbb", File(dstDir, "Photos/2024/b.jpg").readText())

        // Same again: folders merge, files get " (1)".
        run(TransferMode.COPY, ConflictPolicy.KEEP_BOTH_ALL).toList()
        assertEquals("aaa", File(dstDir, "Photos/a (1).jpg").readText())

        // Different volumes, so a move is copy + delete; the source tree must be gone after.
        File(dstDir, "Photos").deleteRecursively()
        val move = run(TransferMode.MOVE, ConflictPolicy.ASK).toList()
        assertEquals(null, move.last().fatalError)
        assertEquals("bbbb", File(dstDir, "Photos/2024/b.jpg").readText())
        assertFalse(File(srcDir, "Photos").exists())
    }
}
