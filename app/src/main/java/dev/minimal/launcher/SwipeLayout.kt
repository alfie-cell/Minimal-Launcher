package dev.minimal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * FrameLayout that detects vertical drags anywhere inside it (stealing them from children once
 * past touch slop) and long-presses on its own empty area. Coordinates are raw screen
 * coordinates, so the layout can translate itself while being dragged.
 */
class SwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        /** Whether a vertical drag that started at raw screen point ([downX], [downY]) should be taken. */
        fun canDrag(up: Boolean, downX: Float, downY: Float): Boolean
        fun onDragStart(up: Boolean) {}
        /** [dy] is total finger movement since touch down; negative is up. */
        fun onDrag(dy: Float) {}
        /** [velocityY] in px/s; negative is up. */
        fun onDragEnd(dy: Float, velocityY: Float) {}
        fun onLongPress(x: Float, y: Float) {}
    }

    var listener: Listener? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    private val longPress = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            if (dragging) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onLongPress(e.x, e.y)
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        longPress.onTouchEvent(ev)
        // Always consume, so touches never fall through to whatever is underneath.
        return true
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging && abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.5f) {
                    val up = dy < 0
                    if (listener?.canDrag(up, downX, downY) == true) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        listener?.onDragStart(up)
                    }
                }
                if (dragging) listener?.onDrag(dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    addMovement(ev)
                    val tracker = velocity
                    var vy = 0f
                    if (tracker != null && ev.actionMasked == MotionEvent.ACTION_UP) {
                        tracker.computeCurrentVelocity(1000)
                        vy = tracker.yVelocity
                    }
                    listener?.onDragEnd(ev.rawY - downY, vy)
                }
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
    }

    private fun addMovement(ev: MotionEvent) {
        val tracker = velocity ?: return
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }
}
