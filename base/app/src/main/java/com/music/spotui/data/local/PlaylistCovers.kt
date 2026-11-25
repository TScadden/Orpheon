package com.music.spotui.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min

/**
 * A playlist's own cover picture, copied into the app's files: the store keeps a path here,
 * never the gallery address (the original can move or be deleted, and list rendering stays
 * fast). The copy is a square of at most [SIZE] px, centre-cropped from the picked image.
 */
object PlaylistCovers {
    private const val SIZE = 512

    /** Copies the image at [uri] into the covers folder; its path, or null when it cannot be read. */
    fun save(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > SIZE * 2 || bounds.outHeight / sample > SIZE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val sized = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        val dir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        FileOutputStream(out).use { dest -> check(sized.compress(Bitmap.CompressFormat.JPEG, 88, dest)) }
        out.absolutePath
    }.getOrNull()

    /** Deletes a cover file ("" or a file already gone is fine). */
    fun delete(path: String) {
        if (path.isNotBlank()) runCatching { File(path).delete() }
    }
}
