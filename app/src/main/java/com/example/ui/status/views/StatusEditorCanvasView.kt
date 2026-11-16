package com.example.ui.status.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive photo status editor canvas supporting:
 * - Cropping with draggable corner handles and aspect presets
 * - 90-degree rotation
 * - Freehand drawing with color, stroke size, and undo
 * - Draggable text and emoji sticker overlays
 * - Composite bitmap export for Firebase Storage upload
 */
class StatusEditorCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class EditorMode {
        VIEW,
        DRAW,
        CROP
    }

    data class StrokeEntry(
        val points: MutableList<Pair<Float, Float>>, // normalized 0..1 relative to imageRect
        val color: Int,
        val strokeWidthNorm: Float
    )

    data class StickerItem(
        val id: Long,
        var text: String,
        var color: Int,
        var hasPillBackground: Boolean,
        var normX: Float, // 0..1 relative to imageRect
        var normY: Float, // 0..1 relative to imageRect
        var textSizeSp: Float = 26f
    )

    var editorMode: EditorMode = EditorMode.VIEW
        set(value) {
            field = value
            if (value == EditorMode.CROP) {
                cropNormRect.set(0.08f, 0.08f, 0.92f, 0.92f)
            }
            invalidate()
        }

    var brushColor: Int = Color.parseColor("#25D366")
    var brushWidthDp: Float = 6f
    var onStrokeCountChanged: ((Int) -> Unit)? = null
    var onStickerTapped: ((StickerItem) -> Unit)? = null

    private var sourceBitmap: Bitmap? = null
    private val imageRect = RectF()
    private val cropNormRect = RectF(0.08f, 0.08f, 0.92f, 0.92f)

    private val strokes = mutableListOf<StrokeEntry>()
    private var activeStroke: StrokeEntry? = null
    private val stickers = mutableListOf<StickerItem>()

    private var draggingSticker: StickerItem? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchMovedDistance = 0f
    private var activeCropCorner = -1 // 0=TL, 1=TR, 2=BR, 3=BL, 4=move whole box

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scrimPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val cropGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val cropHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#25D366")
        style = Paint.Style.FILL
    }
    private val stickerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stickerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val drawPath = Path()
    private val tempRect = RectF()
    private val textBounds = Rect()

    fun setBitmap(bitmap: Bitmap) {
        sourceBitmap = bitmap
        computeImageRect()
        invalidate()
    }

    fun getBitmap(): Bitmap? = sourceBitmap

    fun rotate90Degrees() {
        val bmp = sourceBitmap ?: return
        val matrix = Matrix().apply { postRotate(90f) }
        sourceBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        strokes.clear()
        onStrokeCountChanged?.invoke(0)
        computeImageRect()
        invalidate()
    }

    fun setCropAspectRatio(ratio: Float?) {
        if (ratio == null) {
            cropNormRect.set(0.08f, 0.08f, 0.92f, 0.92f)
        } else {
            val imgW = imageRect.width().coerceAtLeast(1f)
            val imgH = imageRect.height().coerceAtLeast(1f)
            val viewRatio = imgW / imgH
            if (ratio > viewRatio) {
                val halfW = 0.44f
                val halfH = (halfW * viewRatio / ratio).coerceIn(0.12f, 0.45f)
                cropNormRect.set(0.5f - halfW, 0.5f - halfH, 0.5f + halfW, 0.5f + halfH)
            } else {
                val halfH = 0.44f
                val halfW = (halfH * ratio / viewRatio).coerceIn(0.12f, 0.45f)
                cropNormRect.set(0.5f - halfW, 0.5f - halfH, 0.5f + halfW, 0.5f + halfH)
            }
        }
        invalidate()
    }

    fun applyCrop(): Boolean {
        val bmp = sourceBitmap ?: return false
        val left = (cropNormRect.left * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val top = (cropNormRect.top * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val right = (cropNormRect.right * bmp.width).toInt().coerceIn(left + 2, bmp.width)
        val bottom = (cropNormRect.bottom * bmp.height).toInt().coerceIn(top + 2, bmp.height)
        val w = (right - left).coerceAtLeast(2)
        val h = (bottom - top).coerceAtLeast(2)
        sourceBitmap = Bitmap.createBitmap(bmp, left, top, w, h)
        strokes.clear()
        onStrokeCountChanged?.invoke(0)
        editorMode = EditorMode.VIEW
        computeImageRect()
        invalidate()
        return true
    }

    fun undoStroke() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            onStrokeCountChanged?.invoke(strokes.size)
            invalidate()
        }
    }

    fun clearStrokes() {
        strokes.clear()
        onStrokeCountChanged?.invoke(0)
        invalidate()
    }

    fun addSticker(text: String, color: Int = Color.WHITE, hasPillBg: Boolean = true) {
        val offset = (stickers.size % 4) * 0.05f
        stickers.add(
            StickerItem(
                id = System.nanoTime(),
                text = text,
                color = color,
                hasPillBackground = hasPillBg,
                normX = 0.5f,
                normY = (0.45f + offset).coerceIn(0.2f, 0.8f),
                textSizeSp = if (hasPillBg) 22f else 38f
            )
        )
        invalidate()
    }

    fun updateSticker(id: Long, newText: String, newColor: Int) {
        stickers.firstOrNull { it.id == id }?.apply {
            text = newText
            color = newColor
        }
        invalidate()
    }

    fun removeSticker(id: Long) {
        stickers.removeAll { it.id == id }
        invalidate()
    }

    fun renderCompositeBitmap(): Bitmap? {
        val base = sourceBitmap ?: return null
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val targetRect = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        val scaleFactor = out.width.toFloat() / imageRect.width().coerceAtLeast(1f)

        drawStrokesOnCanvas(canvas, targetRect, scaleFactor)
        drawStickersOnCanvas(canvas, targetRect, scaleFactor)
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
    }

    private fun computeImageRect() {
        val bmp = sourceBitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = min(vw / bmp.width, vh / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = (vw - drawW) / 2f
        val top = (vh - drawH) / 2f
        imageRect.set(left, top, left + drawW, top + drawH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sourceBitmap ?: return
        if (imageRect.isEmpty) computeImageRect()

        canvas.drawBitmap(bmp, null, imageRect, bitmapPaint)
        drawStrokesOnCanvas(canvas, imageRect, 1f)
        drawStickersOnCanvas(canvas, imageRect, 1f)

        if (editorMode == EditorMode.CROP) {
            drawCropOverlay(canvas)
        }
    }

    private fun drawStrokesOnCanvas(canvas: Canvas, bounds: RectF, scaleFactor: Float) {
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = (stroke.strokeWidthNorm * bounds.width()).coerceAtLeast(3f * scaleFactor)
            drawPath.reset()
            stroke.points.forEachIndexed { idx, (nx, ny) ->
                val px = bounds.left + nx * bounds.width()
                val py = bounds.top + ny * bounds.height()
                if (idx == 0) drawPath.moveTo(px, py) else drawPath.lineTo(px, py)
            }
            if (stroke.points.size == 1) {
                val px = bounds.left + stroke.points[0].first * bounds.width()
                val py = bounds.top + stroke.points[0].second * bounds.height()
                canvas.drawPoint(px, py, strokePaint)
            } else {
                canvas.drawPath(drawPath, strokePaint)
            }
        }
    }

    private fun drawStickersOnCanvas(canvas: Canvas, bounds: RectF, scaleFactor: Float) {
        val density = resources.displayMetrics.density
        for (sticker in stickers) {
            val cx = bounds.left + sticker.normX * bounds.width()
            val cy = bounds.top + sticker.normY * bounds.height()
            stickerTextPaint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sticker.textSizeSp,
                resources.displayMetrics
            ) * scaleFactor
            stickerTextPaint.color = sticker.color
            stickerTextPaint.getTextBounds(sticker.text, 0, sticker.text.length, textBounds)
            val padX = 16f * density * scaleFactor
            val padY = 10f * density * scaleFactor
            val halfW = max(textBounds.width() / 2f, 20f * scaleFactor) + padX
            val halfH = max(textBounds.height() / 2f, 12f * scaleFactor) + padY

            if (sticker.hasPillBackground) {
                stickerBgPaint.color = Color.argb(175, 17, 27, 33)
                tempRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                canvas.drawRoundRect(tempRect, 14f * density * scaleFactor, 14f * density * scaleFactor, stickerBgPaint)
            }
            val fm = stickerTextPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(sticker.text, cx, textY, stickerTextPaint)
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val left = imageRect.left + cropNormRect.left * imageRect.width()
        val top = imageRect.top + cropNormRect.top * imageRect.height()
        val right = imageRect.left + cropNormRect.right * imageRect.width()
        val bottom = imageRect.top + cropNormRect.bottom * imageRect.height()

        // Scrim outside crop area
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, top, scrimPaint)
        canvas.drawRect(imageRect.left, bottom, imageRect.right, imageRect.bottom, scrimPaint)
        canvas.drawRect(imageRect.left, top, left, bottom, scrimPaint)
        canvas.drawRect(right, top, imageRect.right, bottom, scrimPaint)

        // Crop box & rule-of-thirds grid
        canvas.drawRect(left, top, right, bottom, cropBorderPaint)
        val thirdW = (right - left) / 3f
        val thirdH = (bottom - top) / 3f
        canvas.drawLine(left + thirdW, top, left + thirdW, bottom, cropGridPaint)
        canvas.drawLine(left + thirdW * 2f, top, left + thirdW * 2f, bottom, cropGridPaint)
        canvas.drawLine(left, top + thirdH, right, top + thirdH, cropGridPaint)
        canvas.drawLine(left, top + thirdH * 2f, right, top + thirdH * 2f, cropGridPaint)

        // Corner handles
        val handleR = 11f * resources.displayMetrics.density
        canvas.drawCircle(left, top, handleR, cropHandlePaint)
        canvas.drawCircle(right, top, handleR, cropHandlePaint)
        canvas.drawCircle(right, bottom, handleR, cropHandlePaint)
        canvas.drawCircle(left, bottom, handleR, cropHandlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageRect.isEmpty) return false
        val x = event.x
        val y = event.y

        when (editorMode) {
            EditorMode.DRAW -> {
                val nx = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                val ny = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val widthNorm = (brushWidthDp * resources.displayMetrics.density) /
                            imageRect.width().coerceAtLeast(1f)
                        val stroke = StrokeEntry(
                            points = mutableListOf(nx to ny),
                            color = brushColor,
                            strokeWidthNorm = widthNorm
                        )
                        activeStroke = stroke
                        strokes.add(stroke)
                        onStrokeCountChanged?.invoke(strokes.size)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        activeStroke?.points?.add(nx to ny)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeStroke = null
                        invalidate()
                        return true
                    }
                }
            }

            EditorMode.CROP -> {
                val left = imageRect.left + cropNormRect.left * imageRect.width()
                val top = imageRect.top + cropNormRect.top * imageRect.height()
                val right = imageRect.left + cropNormRect.right * imageRect.width()
                val bottom = imageRect.top + cropNormRect.bottom * imageRect.height()
                val hitR = 32f * resources.displayMetrics.density

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activeCropCorner = when {
                            hypot(x - left, y - top) <= hitR -> 0
                            hypot(x - right, y - top) <= hitR -> 1
                            hypot(x - right, y - bottom) <= hitR -> 2
                            hypot(x - left, y - bottom) <= hitR -> 3
                            x in left..right && y in top..bottom -> 4
                            else -> -1
                        }
                        lastTouchX = x
                        lastTouchY = y
                        return activeCropCorner != -1
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (x - lastTouchX) / imageRect.width()
                        val dy = (y - lastTouchY) / imageRect.height()
                        lastTouchX = x
                        lastTouchY = y
                        val minSpan = 0.16f
                        when (activeCropCorner) {
                            0 -> {
                                cropNormRect.left = (cropNormRect.left + dx).coerceIn(0f, cropNormRect.right - minSpan)
                                cropNormRect.top = (cropNormRect.top + dy).coerceIn(0f, cropNormRect.bottom - minSpan)
                            }
                            1 -> {
                                cropNormRect.right = (cropNormRect.right + dx).coerceIn(cropNormRect.left + minSpan, 1f)
                                cropNormRect.top = (cropNormRect.top + dy).coerceIn(0f, cropNormRect.bottom - minSpan)
                            }
                            2 -> {
                                cropNormRect.right = (cropNormRect.right + dx).coerceIn(cropNormRect.left + minSpan, 1f)
                                cropNormRect.bottom = (cropNormRect.bottom + dy).coerceIn(cropNormRect.top + minSpan, 1f)
                            }
                            3 -> {
                                cropNormRect.left = (cropNormRect.left + dx).coerceIn(0f, cropNormRect.right - minSpan)
                                cropNormRect.bottom = (cropNormRect.bottom + dy).coerceIn(cropNormRect.top + minSpan, 1f)
                            }
                            4 -> {
                                val w = cropNormRect.width()
                                val h = cropNormRect.height()
                                val newL = (cropNormRect.left + dx).coerceIn(0f, 1f - w)
                                val newT = (cropNormRect.top + dy).coerceIn(0f, 1f - h)
                                cropNormRect.set(newL, newT, newL + w, newT + h)
                            }
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeCropCorner = -1
                        return true
                    }
                }
            }

            EditorMode.VIEW -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = x
                        lastTouchY = y
                        touchMovedDistance = 0f
                        val hitSticker = stickers.lastOrNull { item ->
                            val sx = imageRect.left + item.normX * imageRect.width()
                            val sy = imageRect.top + item.normY * imageRect.height()
                            abs(x - sx) <= 72f * resources.displayMetrics.density &&
                                abs(y - sy) <= 36f * resources.displayMetrics.density
                        }
                        draggingSticker = hitSticker
                        return hitSticker != null
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val s = draggingSticker ?: return false
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        touchMovedDistance += hypot(dx, dy)
                        lastTouchX = x
                        lastTouchY = y
                        s.normX = (s.normX + dx / imageRect.width()).coerceIn(0.08f, 0.92f)
                        s.normY = (s.normY + dy / imageRect.height()).coerceIn(0.08f, 0.92f)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val s = draggingSticker
                        draggingSticker = null
                        if (s != null && touchMovedDistance < 12f) {
                            onStickerTapped?.invoke(s)
                        }
                        return s != null
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
