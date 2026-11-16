package com.example.ui.status.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.R
import kotlin.math.min

/**
 * Circular profile picture view with WhatsApp-style segmented status ring
 * (#25D366 for unseen updates, #C4C9CC for viewed updates) and monogram fallback.
 */
class StatusRingAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var avatarBitmap: Bitmap? = null
    private var initials: String = "U"
    private var fillColorInt: Int = ContextCompat.getColor(context, R.color.status_secondary_green)
    private var segmentSeenFlags: List<Boolean> = emptyList()
    private var showDefaultBorder: Boolean = false
    private var ringRevealFraction: Float = 1f
    private var ringAnimator: ValueAnimator? = null

    private val unseenColor = ContextCompat.getColor(context, R.color.status_unseen_ring)
    private val viewedColor = ContextCompat.getColor(context, R.color.status_viewed_ring)

    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val clipPath = Path()
    private val ringBounds = RectF()
    private val imageRect = RectF()

    fun bind(
        displayName: String,
        colorHex: String,
        bitmap: Bitmap?,
        seenFlags: List<Boolean>,
        animateRing: Boolean = false,
        defaultSubtleBorder: Boolean = false
    ) {
        val cleanName = displayName.trim()
        initials = cleanName
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "V" }

        fillColorInt = try {
            Color.parseColor(colorHex.ifBlank { "#128C7E" })
        } catch (_: Exception) {
            ContextCompat.getColor(context, R.color.status_secondary_green)
        }
        avatarBitmap = bitmap
        showDefaultBorder = defaultSubtleBorder

        val changed = segmentSeenFlags != seenFlags
        segmentSeenFlags = seenFlags
        if (animateRing && changed && seenFlags.isNotEmpty()) {
            ringAnimator?.cancel()
            ringAnimator = ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    ringRevealFraction = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            ringRevealFraction = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        ringAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        if (size <= 0f) return

        val density = resources.displayMetrics.density
        val ringStroke = 2.6f * density
        val ringGap = if (segmentSeenFlags.isNotEmpty() || showDefaultBorder) 4.2f * density else 0f
        val cx = w / 2f
        val cy = h / 2f
        val outerRadius = size / 2f - ringStroke / 2f
        val innerRadius = (size / 2f - ringStroke - ringGap).coerceAtLeast(4f)

        // Draw avatar inside inner circle
        canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, innerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        val bmp = avatarBitmap
        if (bmp != null && !bmp.isRecycled) {
            val scale = maxOf((innerRadius * 2f) / bmp.width, (innerRadius * 2f) / bmp.height)
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            imageRect.set(cx - drawW / 2f, cy - drawH / 2f, cx + drawW / 2f, cy + drawH / 2f)
            canvas.drawBitmap(bmp, null, imageRect, bitmapPaint)
        } else {
            circleFillPaint.color = fillColorInt
            canvas.drawCircle(cx, cy, innerRadius, circleFillPaint)
            initialsPaint.textSize = innerRadius * 0.78f
            val fm = initialsPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(initials, cx, textY, initialsPaint)
        }
        canvas.restore()

        // Draw segmented status ring
        ringBounds.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        ringPaint.strokeWidth = ringStroke

        if (segmentSeenFlags.isEmpty()) {
            if (showDefaultBorder) {
                ringPaint.color = unseenColor
                ringPaint.alpha = 90
                canvas.drawArc(ringBounds, 0f, 360f, false, ringPaint)
                ringPaint.alpha = 255
            }
            return
        }

        val count = segmentSeenFlags.size
        if (count == 1) {
            ringPaint.color = if (segmentSeenFlags[0]) viewedColor else unseenColor
            canvas.drawArc(ringBounds, -90f, 360f * ringRevealFraction, false, ringPaint)
        } else {
            val gapDeg = 9f
            val sweepPerSegment = (360f - count * gapDeg) / count
            for (i in 0 until count) {
                val startAngle = -90f + i * (sweepPerSegment + gapDeg) + gapDeg / 2f
                ringPaint.color = if (segmentSeenFlags[i]) viewedColor else unseenColor
                canvas.drawArc(
                    ringBounds,
                    startAngle,
                    sweepPerSegment * ringRevealFraction,
                    false,
                    ringPaint
                )
            }
        }
    }
}
