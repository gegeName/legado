package io.legado.app.ui.widget

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.image.CircleImageView
import io.legado.app.utils.dpToPx
import kotlin.math.abs
import kotlin.math.min

class MiniAudioPlayerView(context: Context) : FrameLayout(context) {

    private val coverView = CircleImageView(context).apply {
        borderWidth = 2.dpToPx()
        borderColor = Color.rgb(224, 205, 157)
        circleBackgroundColor = Color.rgb(34, 31, 28)
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        contentDescription = context.getString(R.string.audio_play)
    }
    private val discShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 0, 0, 0)
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 20, 19, 18)
        style = Paint.Style.STROKE
        strokeWidth = 7f.dpToPx()
    }
    private val discLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 238, 192)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.dpToPx()
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 188, 112)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var collapsed = false
    private var collapsedAtDown = false
    private var collapsedEdge = Edge.RIGHT
    private var expandedX = 0f
    private var expandedY = 0f
    private var playing = false
    private var coverPath: String? = null
    private var sourceOrigin: String? = null
    private val autoHideRunnable = Runnable { collapseToEdge() }
    private val rotateAnimator = ObjectAnimator.ofFloat(coverView, View.ROTATION, 0f, 360f).apply {
        duration = 6_000L
        repeatCount = ObjectAnimator.INFINITE
        interpolator = LinearInterpolator()
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        elevation = 8f.dpToPx()
        val size = 76.dpToPx()
        addView(
            coverView,
            LayoutParams(size, size, Gravity.START or Gravity.BOTTOM).apply {
                marginStart = 8.dpToPx()
                bottomMargin = 8.dpToPx()
            }
        )
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        if (playing) {
            if (!rotateAnimator.isStarted) {
                rotateAnimator.start()
            } else {
                rotateAnimator.resume()
            }
        } else {
            rotateAnimator.pause()
        }
        invalidate()
    }

    fun setCover(path: String?, sourceOrigin: String?) {
        if (coverPath == path && this.sourceOrigin == sourceOrigin) return
        coverPath = path
        this.sourceOrigin = sourceOrigin
        BookCover.load(context, path, sourceOrigin = sourceOrigin)
            .into(coverView)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(autoHideRunnable)
        animate().cancel()
        rotateAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView != this) return
        if (visibility == VISIBLE) {
            scheduleAutoHide()
        } else {
            removeCallbacks(autoHideRunnable)
            animate().cancel()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(autoHideRunnable)
                animate().cancel()
                bringToFront()
                collapsedAtDown = collapsed
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downX = x
                downY = y
                if (collapsed) {
                    updateExpandedPosition()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    if (collapsedAtDown) {
                        expandImmediately()
                        downX = x
                        downY = y
                        downRawX = event.rawX
                        downRawY = event.rawY
                    }
                }
                if (dragging) {
                    moveTo(downX + dx, downY + dy)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    scheduleAutoHide()
                } else if (collapsedAtDown) {
                    expandFromEdge()
                    scheduleAutoHide()
                } else {
                    performClick()
                    scheduleAutoHide()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                scheduleAutoHide()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        val discCx = width / 2f
        val discCy = height / 2f
        val discR = min(39f.dpToPx(), height * 0.36f)
        canvas.drawCircle(discCx + 2f.dpToPx(), discCy + 4f.dpToPx(), discR, discShadowPaint)
        canvas.drawCircle(discCx, discCy, discR, discPaint)
        canvas.drawCircle(discCx, discCy, discR - 9f.dpToPx(), discLightPaint)
        canvas.drawCircle(discCx, discCy, 5f.dpToPx(), knobPaint)
    }

    private fun scheduleAutoHide() {
        removeCallbacks(autoHideRunnable)
        postDelayed(autoHideRunnable, AUTO_HIDE_DELAY)
    }

    private fun moveTo(targetX: Float, targetY: Float) {
        val parentView = parent as? ViewGroup ?: return
        val maxX = (parentView.width - width).coerceAtLeast(0).toFloat()
        val maxY = (parentView.height - height).coerceAtLeast(0).toFloat()
        x = targetX.coerceIn(0f, maxX)
        y = targetY.coerceIn(0f, maxY)
        collapsed = false
    }

    private fun collapseToEdge() {
        val parentView = parent as? ViewGroup ?: return
        if (width == 0 || height == 0 || parentView.width == 0 || parentView.height == 0) {
            scheduleAutoHide()
            return
        }
        val maxX = (parentView.width - width).coerceAtLeast(0).toFloat()
        val maxY = (parentView.height - height).coerceAtLeast(0).toFloat()
        expandedX = x.coerceIn(0f, maxX)
        expandedY = y.coerceIn(0f, maxY)
        val centerX = expandedX + width / 2f
        collapsedEdge = if (centerX < parentView.width / 2f) {
            Edge.LEFT
        } else {
            Edge.RIGHT
        }
        val hideX = width * HIDE_RATIO
        val targetX: Float
        val targetY: Float
        when (collapsedEdge) {
            Edge.LEFT -> {
                targetX = -hideX
                targetY = expandedY
            }
            Edge.RIGHT -> {
                targetX = parentView.width - width + hideX
                targetY = expandedY
            }
        }
        collapsed = true
        animate().x(targetX).y(targetY).setDuration(260L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun expandFromEdge() {
        updateExpandedPosition()
        collapsed = false
        animate().x(expandedX).y(expandedY).setDuration(220L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun expandImmediately() {
        updateExpandedPosition()
        collapsed = false
        x = expandedX
        y = expandedY
    }

    private fun updateExpandedPosition() {
        val parentView = parent as? ViewGroup ?: return
        val maxX = (parentView.width - width).coerceAtLeast(0).toFloat()
        val maxY = (parentView.height - height).coerceAtLeast(0).toFloat()
        when (collapsedEdge) {
            Edge.LEFT -> {
                expandedX = 0f
                expandedY = expandedY.coerceIn(0f, maxY)
            }
            Edge.RIGHT -> {
                expandedX = maxX
                expandedY = expandedY.coerceIn(0f, maxY)
            }
        }
    }

    private enum class Edge {
        LEFT, RIGHT
    }

    companion object {
        private const val AUTO_HIDE_DELAY = 2_000L
        private const val HIDE_RATIO = 0.5f
    }
}
