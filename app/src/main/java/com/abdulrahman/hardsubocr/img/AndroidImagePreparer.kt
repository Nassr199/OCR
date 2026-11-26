package com.abdulrahman.hardsubocr.img

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.ImageCropper
import com.abdulrahman.hardsubocr.core.ImagePreparer
import com.abdulrahman.hardsubocr.core.ImageV26
import com.abdulrahman.hardsubocr.core.LensProtocol
import com.abdulrahman.hardsubocr.core.OcrError
import com.abdulrahman.hardsubocr.core.PngGray
import com.abdulrahman.hardsubocr.core.PreparedImage
import com.abdulrahman.hardsubocr.core.isClassicOcr
import java.io.ByteArrayOutputStream

/**
 * - Google Lens gets JPEG ≤ 1500 px (2.8.0): what Chrome's own Lens upload is (JPEG). A JPEG source
 *   that fits goes as it is, with no re-encoding; anything else is encoded at [LENS_JPEG_QUALITY].
 *   Live bench (37 frames, same size): PNG 216 KB / median 559 ms; JPEG q95 44 KB / 409 ms; q85
 *   24 KB / 385 ms — the same readings (32/37 exact, 28/37 shape; errors 14 → 13). Vision keeps PNG.
 * - enhance = false: Vision gets PNG ≤ 1500 px; Gemini/OpenRouter get the original PNG/JPEG/WebP bytes.
 * - enhance = true : v2.6 prepare_image_for_ocr() (Pillow-identical) → 8-bit grayscale PNG.
 *   [cropBand] keeps only the subtitle band; it is switched off for video crops (already the ROI).
 */
class AndroidImagePreparer(
    private val enhance: Boolean = false,
    private val cropBand: Boolean = true
) : ImagePreparer {

    override fun prepare(bytes: ByteArray, nameHint: String, kind: EngineKind): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw OcrError.Failed("تعذر قراءة الصورة: $nameHint")
        val mime = bounds.outMimeType ?: "image/png"
        val longest = maxOf(w, h)

        // v2.6 enhancement is Gemini's logic: classic OCR always gets the original picture
        if (enhance && !kind.isClassicOcr) return enhanced(bytes, w, h, kind)

        return when {
            // Google Lens: the JPEG as it came, or encoded once (see above)
            kind == EngineKind.LENS -> {
                if (mime == "image/jpeg" && longest <= LensProtocol.MAX_DIMENSION) PreparedImage(bytes, "image/jpeg", w, h)
                else encodeJpeg(bytes, w, h, LensProtocol.MAX_DIMENSION)
            }
            // Vision: PNG ≤ 1500 px
            kind.isClassicOcr -> {
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

    private fun decode(bytes: ByteArray, w: Int, h: Int, maxDim: Int): Bitmap {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw OcrError.Failed("تعذر فك ترميز الصورة")
    }

    private fun enhanced(bytes: ByteArray, w: Int, h: Int, kind: EngineKind): PreparedImage {
        val bmp = decode(bytes, w, h, 4096)
        val bw = bmp.width
        val bh = bmp.height
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        bmp.recycle()
        val out = ImageV26.prepare(
            px, bw, bh,
            ImageV26.Options(
                cropBand = cropBand,
                maxDimension = if (kind.isClassicOcr) LensProtocol.MAX_DIMENSION else 0,
                // the subtitle band never cuts a line of the subtitle (ImageV26.textSafeBand)
                keepText = true
            )
        )
        return PreparedImage(PngGray.encode(out), "image/png", out.width, out.height)
    }

    private fun encodeJpeg(bytes: ByteArray, w: Int, h: Int, maxDim: Int): PreparedImage {
        val decoded = decode(bytes, w, h, maxDim)
        val bmp = scaleToFit(decoded, maxDim)
        try {
            return PreparedImage(toJpeg(bmp), "image/jpeg", bmp.width, bmp.height)
        } finally {
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
        }
    }

    private fun encodePng(bytes: ByteArray, w: Int, h: Int, maxDim: Int): PreparedImage {
        val decoded = decode(bytes, w, h, maxDim)
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

        /** Google Lens upload quality (see the class comment): no loss of reading at 85 and above on the bench. */
        const val LENS_JPEG_QUALITY = 90

        fun toJpeg(bmp: Bitmap, quality: Int = LENS_JPEG_QUALITY): ByteArray {
            val out = ByteArrayOutputStream(bmp.width * bmp.height / 8 + 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }

        fun toPng(bmp: Bitmap): ByteArray {
            val out = ByteArrayOutputStream(bmp.width * bmp.height / 2 + 1024)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
    }
}

/**
 * Lens's extra looks (ClassicViews): a part of the same picture, scaled (bilinear), as JPEG like the
 * first look (2.8.0) — the pixels themselves are not otherwise altered.
 */
object AndroidResampler : com.abdulrahman.hardsubocr.core.ImageResampler {
    override fun view(image: PreparedImage, x0: Int, y0: Int, x1: Int, y1: Int, scale: Float): PreparedImage? {
        val src = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return null
        try {
            val l = x0.coerceIn(0, src.width - 1)
            val t = y0.coerceIn(0, src.height - 1)
            val r = x1.coerceIn(l + 1, src.width)
            val b = y1.coerceIn(t + 1, src.height)
            val part = if (l == 0 && t == 0 && r == src.width && b == src.height) src else Bitmap.createBitmap(src, l, t, r - l, b - t)
            val nw = maxOf(1, Math.round(part.width * scale))
            val nh = maxOf(1, Math.round(part.height * scale))
            val out = if (nw == part.width && nh == part.height) part else Bitmap.createScaledBitmap(part, nw, nh, true)
            try {
                return PreparedImage(AndroidImagePreparer.toJpeg(out), "image/jpeg", out.width, out.height, image.name)
            } finally {
                if (out !== part && out !== src) out.recycle()
                if (part !== src) part.recycle()
            }
        } finally {
            src.recycle()
        }
    }
}

/**
 * Google Vision's focused second look: the same picture cropped to the subtitle's area (Vision's
 * own word boxes), saved as PNG — nothing else about the pixels changes.
 */
object AndroidImageCropper : ImageCropper {
    override fun crop(image: PreparedImage, x0: Int, y0: Int, x1: Int, y1: Int): PreparedImage? {
        val src = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return null
        try {
            val l = x0.coerceIn(0, src.width - 1)
            val t = y0.coerceIn(0, src.height - 1)
            val r = x1.coerceIn(l + 1, src.width)
            val b = y1.coerceIn(t + 1, src.height)
            val part = Bitmap.createBitmap(src, l, t, r - l, b - t)
            try {
                return PreparedImage(AndroidImagePreparer.toPng(part), "image/png", part.width, part.height, image.name)
            } finally {
                if (part !== src) part.recycle()
            }
        } finally {
            src.recycle()
        }
    }
}
