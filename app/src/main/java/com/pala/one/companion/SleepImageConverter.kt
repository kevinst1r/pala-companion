package com.pala.one.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Packs a 250×122 1-bpp sleep image for the reader (LSB first within each byte row).
 * Matches [MainActivity] device output dimensions.
 */
object SleepImageConverter {
    const val WIDTH = 250
    const val HEIGHT = 122
    private const val BYTES_PER_ROW = 32

    /** Luminance cutoff when black tolerance is 0% (ITU-style weights, ≥ → white). */
    const val DEFAULT_BLACK_WHITE_THRESHOLD = 128

    /**
     * Centered tolerance: [toleranceOffsetPercent] in −100…+100, 0 = legacy default (threshold 128).
     * Positive treats more gray as black; negative keeps more gray as white.
     */
    fun blackWhiteThresholdFromToleranceOffset(toleranceOffsetPercent: Int): Int {
        val o = toleranceOffsetPercent.coerceIn(-100, 100)
        val delta = o * (255 - DEFAULT_BLACK_WHITE_THRESHOLD) / 100
        return (DEFAULT_BLACK_WHITE_THRESHOLD + delta).coerceIn(0, 255)
    }

    fun bitmapToPackedSleepImageBytes(
        sourceBitmap: Bitmap,
        toleranceOffsetPercent: Int = 0
    ): ByteArray {
        val threshold = blackWhiteThresholdFromToleranceOffset(toleranceOffsetPercent)
        val target = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        val scale = minOf(
            WIDTH.toFloat() / sourceBitmap.width.toFloat(),
            HEIGHT.toFloat() / sourceBitmap.height.toFloat()
        )
        val drawWidth = (sourceBitmap.width * scale).toInt().coerceAtLeast(1)
        val drawHeight = (sourceBitmap.height * scale).toInt().coerceAtLeast(1)
        val left = (WIDTH - drawWidth) / 2f
        val top = (HEIGHT - drawHeight) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            sourceBitmap,
            null,
            android.graphics.RectF(left, top, left + drawWidth, top + drawHeight),
            paint
        )

        val output = ByteArray(HEIGHT * BYTES_PER_ROW)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val pixel = target.getPixel(x, y)
                val luminance =
                    ((Color.red(pixel) * 299) + (Color.green(pixel) * 587) + (Color.blue(pixel) * 114)) / 1000
                if (luminance >= threshold) {
                    val byteIndex = y * BYTES_PER_ROW + (x / 8)
                    val bitMask = 1 shl (x % 8)
                    output[byteIndex] = (output[byteIndex].toInt() or bitMask).toByte()
                }
            }
        }
        return output
    }

    /**
     * Same luminance rule as [bitmapToPackedSleepImageBytes].
     * [bitmap] must be [WIDTH]×[HEIGHT]; it is modified in place.
     */
    fun thresholdToOneBitInPlace(bitmap: Bitmap, toleranceOffsetPercent: Int = 0) {
        require(bitmap.width == WIDTH && bitmap.height == HEIGHT) {
            "Expected ${WIDTH}x${HEIGHT}, was ${bitmap.width}x${bitmap.height}"
        }
        val threshold = blackWhiteThresholdFromToleranceOffset(toleranceOffsetPercent)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val pixel = bitmap.getPixel(x, y)
                val luminance =
                    ((Color.red(pixel) * 299) + (Color.green(pixel) * 587) + (Color.blue(pixel) * 114)) / 1000
                bitmap.setPixel(x, y, if (luminance >= threshold) Color.WHITE else Color.BLACK)
            }
        }
    }

    /**
     * Flip black/white on a [WIDTH]×[HEIGHT] bitmap (e.g. after [thresholdToOneBitInPlace]).
     */
    fun invertOneBitInPlace(bitmap: Bitmap) {
        require(bitmap.width == WIDTH && bitmap.height == HEIGHT) {
            "Expected ${WIDTH}x${HEIGHT}, was ${bitmap.width}x${bitmap.height}"
        }
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val pixel = bitmap.getPixel(x, y)
                val luminance =
                    ((Color.red(pixel) * 299) + (Color.green(pixel) * 587) + (Color.blue(pixel) * 114)) / 1000
                bitmap.setPixel(x, y, if (luminance >= 128) Color.BLACK else Color.WHITE)
            }
        }
    }
}
