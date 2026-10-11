package dev.ntfsfm.app.ui.files

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

enum class FileCategory { FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, ARCHIVE, APP, CODE, FONT, OTHER }

private val extensionCategories: Map<String, FileCategory> = buildMap {
    fun put(category: FileCategory, vararg exts: String) = exts.forEach { put(it, category) }
    put(FileCategory.IMAGE, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tiff", "ico")
    put(FileCategory.VIDEO, "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "wmv", "flv", "mpg", "mpeg")
    put(FileCategory.AUDIO, "mp3", "flac", "wav", "aac", "ogg", "m4a", "opus", "wma", "aiff")
    put(FileCategory.PDF, "pdf")
    put(FileCategory.DOCUMENT, "doc", "docx", "odt", "rtf", "txt", "md", "log")
    put(FileCategory.SPREADSHEET, "xls", "xlsx", "ods", "csv", "tsv")
    put(FileCategory.PRESENTATION, "ppt", "pptx", "odp")
    put(FileCategory.ARCHIVE, "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "cab", "dmg")
    put(FileCategory.APP, "apk", "apks", "xapk", "exe", "msi", "deb", "rpm")
    put(FileCategory.CODE, "kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "yml", "yaml", "c", "h", "cpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "ps1", "sql")
    put(FileCategory.FONT, "ttf", "otf", "woff", "woff2")
}

fun categoryOf(name: String, isDirectory: Boolean): FileCategory {
    if (isDirectory) return FileCategory.FOLDER
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extensionCategories[ext] ?: FileCategory.OTHER
}

/** Icon plus its colour pair: [tint] for the glyph, [container] for the blob behind it. */
data class FileVisual(val icon: ImageVector, val tint: Color, val container: Color)

private fun visual(icon: ImageVector, color: Color, dark: Boolean) =
    FileVisual(icon, color, color.copy(alpha = if (dark) 0.26f else 0.16f))

@Composable
fun visualFor(name: String, isDirectory: Boolean): FileVisual = visualFor(categoryOf(name, isDirectory))

@Composable
fun visualFor(category: FileCategory): FileVisual {
    val dark = isSystemInDarkTheme()
    return when (category) {
        FileCategory.FOLDER -> visual(Icons.Rounded.Folder, Color(0xFFF5A524), dark)
        FileCategory.IMAGE -> visual(Icons.Rounded.Image, Color(0xFFEC4899), dark)
        FileCategory.VIDEO -> visual(Icons.Rounded.Movie, Color(0xFFF97316), dark)
        FileCategory.AUDIO -> visual(Icons.Rounded.MusicNote, Color(0xFF8B5CF6), dark)
        FileCategory.PDF -> visual(Icons.Rounded.PictureAsPdf, Color(0xFFEF4444), dark)
        FileCategory.DOCUMENT -> visual(Icons.Rounded.Description, Color(0xFF3B82F6), dark)
        FileCategory.SPREADSHEET -> visual(Icons.Rounded.TableChart, Color(0xFF22C55E), dark)
        FileCategory.PRESENTATION -> visual(Icons.Rounded.Slideshow, Color(0xFFFB923C), dark)
        FileCategory.ARCHIVE -> visual(Icons.Rounded.FolderZip, Color(0xFFCA8A04), dark)
        FileCategory.APP -> visual(Icons.Rounded.Android, Color(0xFF10B981), dark)
        FileCategory.CODE -> visual(Icons.Rounded.Code, Color(0xFF06B6D4), dark)
        FileCategory.FONT -> visual(Icons.Rounded.FontDownload, Color(0xFF6366F1), dark)
        FileCategory.OTHER -> visual(Icons.Rounded.InsertDriveFile, Color(0xFF64748B), dark)
    }
}

/** The shortcut tiles on the home screen; each is a search with a predicate. */
enum class HomeCategory(val label: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    DOWNLOADS("Downloads"),
    APPS("Installation files"),
    ARCHIVES("Compressed");

    fun matches(name: String, relativePath: String): Boolean = when (this) {
        IMAGES -> categoryOf(name, false) == FileCategory.IMAGE
        VIDEOS -> categoryOf(name, false) == FileCategory.VIDEO
        AUDIO -> categoryOf(name, false) == FileCategory.AUDIO
        DOCUMENTS -> categoryOf(name, false) in setOf(FileCategory.DOCUMENT, FileCategory.PDF, FileCategory.SPREADSHEET, FileCategory.PRESENTATION)
        DOWNLOADS -> relativePath.startsWith("Download/", ignoreCase = true) || relativePath.startsWith("Downloads/", ignoreCase = true)
        APPS -> categoryOf(name, false) == FileCategory.APP
        ARCHIVES -> categoryOf(name, false) == FileCategory.ARCHIVE
    }
}

@Composable
fun visualFor(category: HomeCategory): FileVisual = when (category) {
    HomeCategory.IMAGES -> visualFor(FileCategory.IMAGE)
    HomeCategory.VIDEOS -> visualFor(FileCategory.VIDEO)
    HomeCategory.AUDIO -> visualFor(FileCategory.AUDIO)
    HomeCategory.DOCUMENTS -> visualFor(FileCategory.DOCUMENT)
    HomeCategory.DOWNLOADS -> visual(Icons.Rounded.Download, Color(0xFF14B8A6), isSystemInDarkTheme())
    HomeCategory.APPS -> visualFor(FileCategory.APP)
    HomeCategory.ARCHIVES -> visualFor(FileCategory.ARCHIVE)
}
