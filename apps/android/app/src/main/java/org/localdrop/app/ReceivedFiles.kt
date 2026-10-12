package org.localdrop.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

data class ReceivedFile(val name: String, val uri: Uri, val mime: String,
                        val size: Long, val modified: Long)

object ReceivedFiles {
    fun mime(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    fun list(context: Context): List<ReceivedFile> = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val result = mutableListOf<ReceivedFile>()
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED)
            context.contentResolver.query(collection, columns,
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=0",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/LocalDrop/"),
                "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { cursor ->
                while (cursor.moveToNext() && result.size < 100) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    result += ReceivedFile(cursor.getString(1), uri,
                        cursor.getString(2) ?: "application/octet-stream",
                        if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        if (cursor.isNull(4)) 0 else cursor.getLong(4) * 1000)
                }
            }
            result
        } else {
            val parent = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: return emptyList()
            val directory = File(parent, "LocalDrop")
            directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".partial") }
                ?.sortedByDescending { it.lastModified() }?.take(100)?.map { file ->
                    ReceivedFile(file.name, FileProvider.getUriForFile(context,
                        "org.localdrop.app.fileprovider", file), mime(file.name),
                        file.length(), file.lastModified())
                } ?: emptyList()
        }
    } catch (_: Exception) { emptyList() }
}
