package com.abdulrahman.hardsubocr.img

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.ImagePreparer
import com.abdulrahman.hardsubocr.core.LensProtocol
import com.abdulrahman.hardsubocr.core.OcrError
import com.abdulrahman.hardsubocr.core.PreparedImage
import java.io.ByteArrayOutputStream

/**
 * Lens: PNG, longest side <= 1500 px (same as chrome-lens-py).
 * Gemini / OpenRouter: original PNG/JPEG/WebP bytes are sent untouched (no quality loss);
 * BMP or huge images are re-encoded to PNG.
 */
class AndroidImagePreparer : ImagePreparer {

    override fun prepare(bytes: ByteArray, nameHint: String, kind: EngineKind): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw OcrError.Failed("تعذر قراءة الصورة: $nameHint")
        val mime = bounds.outMimeType ?: "image/png"
        val longest = maxOf(w, h)

        return when (kind) {
            EngineKind.LENS -> {
                if (mime == "image/png" && longest <= LensProtocol.MAX_DIMENSION) {
                    PreparedImage(bytes, "image/png", w, h)
                } else {
                    encodePng(bytes, w, h, LensProtocol.MAX_DIMENSION)
                }
            }
            else -> {
                val passThrough = mime == "image/png" || mime == "image/jpeg" || mime == "image/webp"
                if (passThrough && longest <= 3072) PreparedImage(bytes, mime, w, h)
                else encodePng(bytes, w, h, 3072)
            }
        }
    }

    private fun encodePng(bytes: ByteArray, w: Int, h: Int, maxDim: Int): PreparedImage {
        val longest = maxOf(w, h)
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw OcrError.Failed("تعذر فك ترميز الصورة")
        val bmp = scaleToFit(decoded, maxDim)
        try {
            return PreparedImage(toPng(bmp), "image/png", bmp.width, bmp.height)
        } finally {
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
        }
    }

    companion object {
        fun scaleToFit(src: Bitmap, maxDim: Int): Bitmap {
            val longest = maxOf(src.width, src.height)
            if (longest <= maxDim) return src
            val scale = maxDim.toFloat() / longest
            val nw = maxOf(1, Math.round(src.width * scale))
            val nh = maxOf(1, Math.round(src.height * scale))
            return Bitmap.createScaledBitmap(src, nw, nh, true)
        }

        fun toPng(bmp: Bitmap): ByteArray {
            val out = ByteArrayOutputStream(bmp.width * bmp.height / 2 + 1024)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
    }
}
