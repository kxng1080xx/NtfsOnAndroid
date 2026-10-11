package dev.ntfsfm.core.transfer

import dev.ntfsfm.core.vfs.NtfsNameValidator
import dev.ntfsfm.core.vfs.VfsEntry
import dev.ntfsfm.core.vfs.VfsException
import dev.ntfsfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

private const val COPY_BUFFER_SIZE = 1024 * 1024
private const val PROGRESS_EMIT_INTERVAL_MS = 200L

/**
 * Copies or moves files and folders between any two volumes. Plans the whole tree first so
 * progress has a total, then works item by item. One failed item doesn't stop the rest;
 * a volume disappearing does.
 */
class TransferEngine {

    private data class PlanItem(val entry: VfsEntry, val relativePath: String, val topLevelPath: String)

    fun execute(
        source: VirtualFileSystem,
        sourcePaths: List<String>,
        dest: VirtualFileSystem,
        destDir: String,
        mode: TransferMode,
        controller: TransferController,
        conflictPolicy: ConflictPolicy,
        onAskConflict: suspend (existing: VfsEntry) -> ConflictAnswer,
    ): Flow<TransferProgress> = flow {
        val plan = planTransfer(source, sourcePaths)
        val bytesTotal = plan.sumOf { if (it.entry.isDirectory) 0L else it.entry.sizeBytes }
        val itemsTotal = plan.size
        val filesTotal = plan.count { !it.entry.isDirectory }
        var filesCompleted = 0
        var lastEmit = 0L
        // Speed = exponential moving average of the rate between samples, so it tracks the
        // current file rather than averaging in the planning time and earlier files.
        var speedSampleTime = System.currentTimeMillis()
        var speedSampleBytes = 0L
        var speed = 0.0
        val sameVolume = source.identity.volumeId == dest.identity.volumeId
        var effectivePolicy = conflictPolicy
        var bytesTransferred = 0L
        var itemsCompleted = 0
        var itemsFailed = 0
        // Top-level items not to delete from the source at the end of a move.
        val failedTopLevelPaths = mutableSetOf<String>()
        // Top-level items moved with one rename; their children need no work.
        val movedInPlace = mutableSetOf<String>()

        fun progress(item: TransferItemProgress?, isDone: Boolean = false, fatalError: String? = null): TransferProgress {
            val now = System.currentTimeMillis()
            val dt = now - speedSampleTime
            if (dt >= 250) {
                val instant = (bytesTransferred - speedSampleBytes) * 1000.0 / dt
                speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
                speedSampleTime = now
                speedSampleBytes = bytesTransferred
            }
            return TransferProgress(
            itemsCompleted = itemsCompleted,
            itemsTotal = itemsTotal,
            bytesTransferred = bytesTransferred,
            bytesTotal = bytesTotal,
            throughputBytesPerSecond = speed.toLong(),
            currentItem = item,
            isDone = isDone,
            fatalError = fatalError,
            itemsFailed = itemsFailed,
            filesCompleted = filesCompleted,
            filesTotal = filesTotal,
            )
        }

        for (planItem in plan) {
            currentCoroutineContext().ensureActive()
            awaitIfPaused(controller)
            val entry = planItem.entry
            var itemState = ItemState.DONE
            var error: String? = null
            try {
                if (planItem.topLevelPath in movedInPlace) {
                    if (!entry.isDirectory) bytesTransferred += entry.sizeBytes
                } else {
                    val destPath = joinPath(destDir, planItem.relativePath)
                    val isTopLevel = '/' !in planItem.relativePath
                    val canRename = mode == TransferMode.MOVE && sameVolume && isTopLevel
                    if (entry.isDirectory) {
                        if (canRename && !exists(dest, destPath) && runCatching { dest.moveWithin(entry.path, destPath) }.getOrDefault(false)) {
                            movedInPlace += planItem.topLevelPath
                        } else {
                            ensureDirectoryExists(dest, destPath)
                        }
                    } else {
                        val finalDestPath = resolveConflict(dest, destPath, effectivePolicy) { existing ->
                            val answer = onAskConflict(existing)
                            if (answer.applyToAll) effectivePolicy = answer.decision.toPolicy()
                            answer.decision
                        }
                        when {
                            finalDestPath == null -> {
                                itemState = ItemState.SKIPPED
                                failedTopLevelPaths += planItem.topLevelPath // a skipped file must survive the move
                            }
                            canRename && moveInPlace(dest, entry.path, finalDestPath) -> {
                                movedInPlace += planItem.topLevelPath
                                bytesTransferred += entry.sizeBytes
                            }
                            else -> {
                                val bytesBefore = bytesTransferred
                                copyFileStreaming(source, entry.path, dest, finalDestPath, controller) { delta ->
                                    bytesTransferred += delta
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                                        lastEmit = now
                                        emit(progress(TransferItemProgress(entry.path, entry.name, bytesTransferred - bytesBefore, entry.sizeBytes, ItemState.IN_PROGRESS)))
                                    }
                                }
                                // Best effort: keep the modification time.
                                runCatching { dest.setTimestamps(finalDestPath, source.stat(entry.path).timestamps) }
                                if (mode == TransferMode.MOVE) {
                                    runCatching { source.delete(entry.path) }.onFailure { failedTopLevelPaths += planItem.topLevelPath }
                                }
                            }
                        }
                    }
                }
            } catch (e: VfsException.VolumeUnavailable) {
                emit(progress(null, isDone = true, fatalError = e.message))
                return@flow
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                itemState = ItemState.FAILED
                itemsFailed++
                error = e.message ?: e.javaClass.simpleName
                failedTopLevelPaths += planItem.topLevelPath
            }
            itemsCompleted++
            if (!entry.isDirectory) filesCompleted++
            val now = System.currentTimeMillis()
            if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS || itemsCompleted == itemsTotal || itemState == ItemState.FAILED) {
                lastEmit = now
                val doneBytes = if (itemState == ItemState.DONE) entry.sizeBytes else 0L
                emit(progress(TransferItemProgress(entry.path, entry.name, doneBytes, entry.sizeBytes, itemState, error)))
            }
        }

        // A move leaves the emptied source folders behind; remove those that fully made it.
        if (mode == TransferMode.MOVE) {
            for (path in sourcePaths) {
                if (path in failedTopLevelPaths || path in movedInPlace) continue
                runCatching { source.delete(path) }
            }
        }
        emit(progress(null, isDone = true))
    }

    private suspend fun awaitIfPaused(controller: TransferController) {
        controller.isPaused.first { paused -> !paused }
    }

    private suspend fun moveInPlace(dest: VirtualFileSystem, from: String, to: String): Boolean =
        runCatching {
            // resolveConflict chose OVERWRITE if the target exists; a rename won't replace it.
            if (exists(dest, to)) dest.delete(to)
            dest.moveWithin(from, to)
        }.getOrDefault(false)

    private suspend fun planTransfer(source: VirtualFileSystem, sourcePaths: List<String>): List<PlanItem> {
        val plan = mutableListOf<PlanItem>()
        for (path in sourcePaths) {
            planRecursive(source, source.stat(path), path.substringAfterLast('/'), path, plan)
        }
        return plan
    }

    /** Parents before children, so directories exist before their files are copied. */
    private suspend fun planRecursive(source: VirtualFileSystem, entry: VfsEntry, relativePath: String, topLevelPath: String, out: MutableList<PlanItem>) {
        out += PlanItem(entry, relativePath, topLevelPath)
        if (entry.isDirectory) {
            for (child in source.listDirectory(entry.path).toList()) {
                planRecursive(source, child, "$relativePath/${child.name}", topLevelPath, out)
            }
        }
    }

    private suspend fun exists(vfs: VirtualFileSystem, path: String): Boolean = runCatching { vfs.stat(path) }.isSuccess

    private suspend fun ensureDirectoryExists(dest: VirtualFileSystem, path: String) {
        val existing = runCatching { dest.stat(path) }.getOrNull()
        if (existing == null) {
            dest.createDirectory(path)
        } else if (!existing.isDirectory) {
            throw VfsException.AlreadyExists(path)
        }
    }

    /** Where to write, or null to skip. */
    private suspend fun resolveConflict(
        dest: VirtualFileSystem,
        candidatePath: String,
        policy: ConflictPolicy,
        onAsk: suspend (VfsEntry) -> ConflictDecision,
    ): String? {
        val existing = runCatching { dest.stat(candidatePath) }.getOrNull() ?: return candidatePath
        val decision = when (policy) {
            ConflictPolicy.SKIP_ALL -> ConflictDecision.SKIP
            ConflictPolicy.OVERWRITE_ALL -> ConflictDecision.OVERWRITE
            ConflictPolicy.KEEP_BOTH_ALL -> ConflictDecision.KEEP_BOTH
            ConflictPolicy.ASK -> onAsk(existing)
        }
        return when (decision) {
            ConflictDecision.SKIP -> null
            ConflictDecision.OVERWRITE -> candidatePath
            ConflictDecision.KEEP_BOTH -> uniqueName(dest, candidatePath)
        }
    }

    /** "name (1).ext", "name (2).ext", ... the first that doesn't exist. */
    private suspend fun uniqueName(dest: VirtualFileSystem, path: String): String {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var counter = 1
        while (true) {
            val candidateName = NtfsNameValidator.sanitize("$base ($counter)$ext")
            val candidatePath = if (parent.isEmpty()) candidateName else "$parent/$candidateName"
            if (!exists(dest, candidatePath)) return candidatePath
            counter++
        }
    }

    private suspend fun copyFileStreaming(
        source: VirtualFileSystem,
        sourcePath: String,
        dest: VirtualFileSystem,
        destPath: String,
        controller: TransferController,
        onBytesWritten: suspend (Long) -> Unit,
    ) {
        source.openInputStream(sourcePath).use { input ->
            dest.openOutputStream(destPath).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    awaitIfPaused(controller)
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    onBytesWritten(read.toLong())
                }
            }
        }
    }

    private fun joinPath(parent: String, child: String): String {
        val trimmed = parent.trim('/')
        return if (trimmed.isEmpty()) child else "$trimmed/$child"
    }
}
