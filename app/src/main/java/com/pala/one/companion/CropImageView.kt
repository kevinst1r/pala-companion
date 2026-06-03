package com.pala.one.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withSave
import kotlin.math.min

/**
 * Pan and pinch-zoom over a bitmap with a fixed-aspect "device window" ([SleepImageConverter] size).
 * The window shows the same 1-bit threshold preview as the packed file; surrounding image stays full color with a 50% dim overlay.
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var bitmap: android.graphics.Bitmap? = null
        set(value) {
            field = value
            invalidateOneBitPreviewCache()
            pendingFit = true
            requestLayout()
        }

    /** When true, black and white in the device preview and saved file are swapped after thresholding. */
    var invertOneBit: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateOneBitPreviewCache()
            invalidate()
        }

    /**
     * −100…+100, centered at 0 (same default threshold as before). Positive: more black;
     * negative: more white.
     */
    var blackToleranceOffsetPercent: Int = 0
        set(value) {
            val clamped = value.coerceIn(-100, 100)
            if (field == clamped) return
            field = clamped
            invalidateOneBitPreviewCache()
            invalidate()
        }

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Nearest-neighbor upscale so 250×122 device pixels read as hard edges on screen. */
    private val oneBitPreviewPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 0, 0, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val imageMatrix = Matrix()
    private var cachedOneBitPreview: android.graphics.Bitmap? = null
    private val cropRect = RectF()
    private var pendingFit = false

    private val aspectDevice: Float =
        SleepImageConverter.WIDTH.toFloat() / SleepImageConverter.HEIGHT.toFloat()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isNaN() || factor == 0f) return false
                val clamped = factor.coerceIn(0.9f, 1.1f)
                imageMatrix.postScale(clamped, clamped, detector.focusX, detector.focusY)
                invalidateOneBitPreviewCache()
                invalidate()
                return true
            }
        }
    )

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCropRect()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (pendingFit && bitmap != null && width > 0 && height > 0) {
            fitImageInCropWindow()
            pendingFit = false
        }
    }

    private fun updateCropRect() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val viewAspect = w / h
        val margin = 0.92f
        if (viewAspect > aspectDevice) {
            val ch = h * margin
            val cw = ch * aspectDevice
            val left = (w - cw) / 2f
            val top = (h - ch) / 2f
            cropRect.set(left, top, left + cw, top + ch)
        } else {
            val cw = w * margin
            val ch = cw / aspectDevice
            val left = (w - cw) / 2f
            val top = (h - ch) / 2f
            cropRect.set(left, top, left + cw, top + ch)
        }
        invalidateOneBitPreviewCache()
    }

    private fun fitImageInCropWindow() {
        val bmp = bitmap ?: return
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val cw = cropRect.width()
        val ch = cropRect.height()
        val s = min(cw / bw, ch / bh)
        val wScaled = bw * s
        val hScaled = bh * s
        imageMatrix.reset()
        imageMatrix.postScale(s, s)
        imageMatrix.postTranslate(
            cropRect.centerX() - wScaled / 2f,
            cropRect.centerY() - hScaled / 2f
        )
        invalidateOneBitPreviewCache()
        invalidate()
    }

    fun resetToFit() {
        pendingFit = true
        updateCropRect()
        fitImageInCropWindow()
        pendingFit = false
    }

    fun rotateClockwise() {
        val bmp = bitmap ?: return
        val rotated = runCatching {
            val rotateMatrix = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, rotateMatrix, true)
        }.getOrNull() ?: return
        if (rotated != bmp && !bmp.isRecycled) {
            bmp.recycle()
        }
        bitmap = rotated
    }

    private fun invalidateOneBitPreviewCache() {
        cachedOneBitPreview?.recycle()
        cachedOneBitPreview = null
    }

    override fun onDetachedFromWindow() {
        invalidateOneBitPreviewCache()
        super.onDetachedFromWindow()
    }

    /**
     * Renders the portion of the bitmap visible inside [cropRect] into a 250×122 bitmap (white letterbox).
     */
    fun exportSleepSizedBitmap(): android.graphics.Bitmap {
        val bmp = bitmap ?: error("No bitmap loaded")
        val cw = cropRect.width()
        val ch = cropRect.height()
        require(cw > 0f && ch > 0f) { "Crop rect not ready" }

        val out = android.graphics.Bitmap.createBitmap(
            SleepImageConverter.WIDTH,
            SleepImageConverter.HEIGHT,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val shiftToCrop = Matrix().apply { setTranslate(-cropRect.left, -cropRect.top) }
        val viewFromBitmap = Matrix()
        viewFromBitmap.setConcat(shiftToCrop, imageMatrix)

        val scaleToOut = Matrix().apply {
            setScale(
                SleepImageConverter.WIDTH / cw,
                SleepImageConverter.HEIGHT / ch,
                0f,
                0f
            )
        }
        val drawMatrix = Matrix()
        drawMatrix.setConcat(scaleToOut, viewFromBitmap)

        canvas.withSave {
            canvas.clipRect(0, 0, SleepImageConverter.WIDTH, SleepImageConverter.HEIGHT)
            canvas.drawBitmap(bmp, drawMatrix, drawPaint)
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val bmp = bitmap ?: return

        canvas.withSave {
            canvas.concat(imageMatrix)
            canvas.drawBitmap(bmp, 0f, 0f, drawPaint)
        }

        if (cachedOneBitPreview == null) {
            val small = exportSleepSizedBitmap()
            SleepImageConverter.thresholdToOneBitInPlace(small, blackToleranceOffsetPercent)
            if (invertOneBit) {
                SleepImageConverter.invertOneBitInPlace(small)
            }
            cachedOneBitPreview = small
        }
        val oneBit = cachedOneBitPreview ?: return
        canvas.withSave {
            canvas.clipRect(cropRect)
            canvas.drawBitmap(oneBit, null, cropRect, oneBitPreviewPaint)
        }

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, dimPaint)

        canvas.drawRect(cropRect, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val i = event.findPointerIndex(activePointerId)
                    if (i >= 0) {
                        val x = event.getX(i)
                        val y = event.getY(i)
                        imageMatrix.postTranslate(x - lastTouchX, y - lastTouchY)
                        lastTouchX = x
                        lastTouchY = y
                        invalidateOneBitPreviewCache()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                        activePointerId = event.getPointerId(newIndex)
                    }
                }
            }
        }
        return true
    }
}
