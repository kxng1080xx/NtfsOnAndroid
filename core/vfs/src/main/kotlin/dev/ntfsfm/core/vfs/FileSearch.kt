package dev.ntfsfm.core.vfs

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/** Breadth-first name search, so shallow matches show up first. */
class FileSearch(private val vfs: VirtualFileSystem) {

    fun search(
        query: String,
        rootPath: String = "",
        predicate: ((VfsEntry) -> Boolean)? = null,
        maxResults: Int = 500,
        maxDepth: Int = 32,
    ): Flow<VfsEntry> = flow {
        if (query.isBlank() && predicate == null) return@flow
        val needle = query.trim().lowercase(Locale.ROOT)
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(rootPath to 0)
        var emitted = 0
        while (queue.isNotEmpty() && emitted < maxResults) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = queue.removeFirst()
            val children = try {
                vfs.listDirectory(dir).toList()
            } catch (_: VfsException) {
                continue // unreadable directory: skip it, keep searching the rest
            }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                val matches = predicate?.invoke(child) ?: child.name.lowercase(Locale.ROOT).contains(needle)
                if (matches) {
                    emit(child)
                    if (++emitted >= maxResults) return@flow
                }
                if (child.isDirectory && depth < maxDepth) queue.addLast(child.path to depth + 1)
            }
        }
    }
}
