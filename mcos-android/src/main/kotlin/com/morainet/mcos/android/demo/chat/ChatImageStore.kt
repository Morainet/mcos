package com.morainet.mcos.android.demo.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.morainet.mcos.android.host.AndroidKeystoreValueCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps resized attachments in app-private encrypted cache, separate from the conversation snapshot. */
internal class ChatImageStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.cacheDir, "chat-images")
    private val cipher by lazy { AndroidKeystoreValueCipher("mcos.chat-images.v1") }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) decode(uri) else decodeLegacy(uri)
        val bytes = try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output) || output.size() !in 1..MAX_IMAGE_BYTES) {
                throw IOException("The image could not be resized.")
            }
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Image cache is unavailable.")
        val id = UUID.randomUUID().toString()
        val file = File(directory, id)
        file.writeBytes(cipher.encrypt(bytes))
        directory.listFiles()?.filter { it.isFile && it != file }?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_IMAGES - 1)?.forEach { it.delete() }
        id
    }

    suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!imageIdPattern.matches(id)) return@withContext null
        val file = File(directory, id)
        if (!file.isFile || file.length() !in 1..(MAX_IMAGE_BYTES + 64L)) return@withContext null
        runCatching { cipher.decrypt(file.readBytes()) }.getOrNull()
    }

    @RequiresApi(28)
    private fun decode(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) {
        decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val size = info.size
        val longest = maxOf(size.width, size.height)
        if (longest > MAX_EDGE) {
            decoder.setTargetSize(
                (size.width.toLong() * MAX_EDGE / longest).toInt().coerceAtLeast(1),
                (size.height.toLong() * MAX_EDGE / longest).toInt().coerceAtLeast(1),
            )
        }
    }

    private fun decodeLegacy(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("The selected image is unreadable.")
        val longest = maxOf(options.outWidth, options.outHeight)
        options.inSampleSize = 1
        while (longest / options.inSampleSize > MAX_EDGE) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("The selected image is unreadable.")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull()
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (transform.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_EDGE = 1_600
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_IMAGES = 32
        val imageIdPattern = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}
