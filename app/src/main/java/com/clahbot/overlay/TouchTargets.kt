package com.clahbot.overlay

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup

/**
 * Expands hit areas to a minimum physical size without changing the compact
 * visuals. Each parent gets one aggregate delegate that forwards to its
 * children's expanded rectangles.
 */
object TouchTargets {

    fun install(root: View, minSizePx: Int) {
        if (minSizePx <= 0) return
        visit(root, minSizePx)
    }

    private fun visit(view: View, minSizePx: Int) {
        if (view is ViewGroup) {
            val delegates = mutableListOf<TouchDelegate>()
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility != View.VISIBLE) continue
                if (!child.isClickable && !child.hasOnClickListeners()) continue
                if (child.width <= 0 || child.height <= 0) continue
                if (child.width >= minSizePx && child.height >= minSizePx) continue
                val dx = ((minSizePx - child.width) / 2).coerceAtLeast(0)
                val dy = ((minSizePx - child.height) / 2).coerceAtLeast(0)
                val rect = Rect(
                    (child.left - dx).coerceAtLeast(0),
                    (child.top - dy).coerceAtLeast(0),
                    (child.right + dx).coerceAtMost(view.width),
                    (child.bottom + dy).coerceAtMost(view.height)
                )
                if (rect.width() <= child.width && rect.height() <= child.height) continue
                delegates += TouchDelegate(rect, child)
            }
            view.touchDelegate = if (delegates.isEmpty()) {
                null
            } else {
                AggregateTouchDelegate(
                    Rect(0, 0, view.width, view.height),
                    view,
                    delegates
                )
            }
            for (index in 0 until view.childCount) {
                visit(view.getChildAt(index), minSizePx)
            }
        }
    }

    private class AggregateTouchDelegate(
        bounds: Rect,
        host: View,
        private val delegates: List<TouchDelegate>
    ) : TouchDelegate(bounds, host) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            for (delegate in delegates) {
                if (delegate.onTouchEvent(event)) return true
            }
            return false
        }
    }
}
