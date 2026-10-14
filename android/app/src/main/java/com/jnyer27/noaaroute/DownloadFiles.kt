package com.jnyer27.noaaroute

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** Files the app publishes under Download/ (routes, chart maps), via MediaStore. */
object DownloadFiles {
    /**
     * A file under Download/[subdir] named [base].[ext] to (over)write, and its actual
     * name. Re-saving updates this app's own copy. If the name is taken by a file the
     * app does not own (e.g. from Termux or an earlier install), MediaStore would rename
     * ours to "name.ext (1)", which no longer ends in .ext; instead pick name-2.ext,
     * name-3.ext, ... and confirm MediaStore kept the name.
     */
    fun target(ctx: Context, subdir: String, base: String, ext: String, mime: String): Pair<Uri, String> {
        val cr = ctx.contentResolver
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + subdir + "/"
        val coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val ours = Regex(Regex.escape(base) + "(-\\d+)?\\." + Regex.escape(ext))
        // Our own earlier copy (only files this app created are visible)
        cr.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(rel, "$base%"), "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1)
                if (ours.matches(n)) return Uri.withAppendedPath(coll, c.getLong(0).toString()) to n
            }
        }
        for (k in 1..50) {
            val fileName = if (k == 1) "$base.$ext" else "$base-$k.$ext"
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }
            val uri = cr.insert(coll, v) ?: continue
            var actual: String? = null
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) actual = c.getString(0)
            }
            if (actual == fileName) return uri to fileName
            cr.delete(uri, null, null) // MediaStore renamed it: name taken, try the next
        }
        throw Exception("Could not create $base.$ext in Download/$subdir")
    }

    /** The plain path of a file the app published (readable directly on Android 11+). */
    fun path(subdir: String, name: String) =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path + "/" + subdir + "/" + name
}
