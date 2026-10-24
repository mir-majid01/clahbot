package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.TouchInteractionController
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.clahbot.execution.LiveGestureTracker
import com.clahbot.execution.MacroTimeline

/**
 * Records real touches while letting them reach the app underneath.
 *
 * The service temporarily requests touch exploration and uses the
 * TouchInteractionController to hand the active pointer to the normal input
 * pipeline with requestDragging(). The framework then keeps sending the raw
 * pointer stream to this callback, so AutoClash can record the same gesture
 * that the game receives. Touches starting on the floating controller are
 * excluded through a passthrough region and never recorded.
 *
 * A completed tap that was not relayed (because it never crossed the drag
 * slop) is dispatched once on release, which is when taps normally act.
 */
class LiveTouchRelay(
    private val service: AccessibilityService,
    private val shouldCapture: (Float, Float) -> Boolean,
    private val onGesture: (LiveGestureTracker.Snapshot) -> Unit,
    private val onUnavailable: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop.toFloat()
    private var tracker = LiveGestureTracker(touchSlop)
    private var controller: TouchInteractionController? = null

    private var enabled = false
    private var pending = false
    private var passThroughStarted = false
    private var pointerId = -1
    private var lastX = 0f
    private var lastY = 0f

    private var controllerRegion = Region()
    private var checkedAvailability = false

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.w(TAG, "TouchInteractionController requires Android 13+")
            return false
        }
        val tic = try {
            service.getTouchInteractionController(Display.DEFAULT_DISPLAY)
        } catch (t: Throwable) {
            Log.e(TAG, "No touch interaction controller", t)
            null
        } ?: return false
        controller = tic
        tracker = LiveGestureTracker(touchSlop)
        checkedAvailability = false
        return enable()
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        if (value) enable() else disable()
    }

    fun stop() {
        disable()
        controller = null
        controllerRegion = Region()
    }

    fun updateControllerRegion(rect: Rect?) {
        controllerRegion = if (rect == null) Region() else Region(rect)
        applyControllerRegion()
    }

    fun onTouchInteractionEnd(eventTime: Long) {
        if (!pending) return
        log("interaction end t=$eventTime relayed=$passThroughStarted")
        finishPending(eventTime)
    }

    private fun enable(): Boolean {
        if (enabled) return true
        checkedAvailability = false
        val tic = controller ?: return false
        val info = service.serviceInfo ?: return false
        return try {
            val callback = object : TouchInteractionController.Callback {
                override fun onMotionEvent(event: MotionEvent) {
                    this@LiveTouchRelay.onMotionEvent(event)
                }

                override fun onStateChanged(state: Int) {
                    this@LiveTouchRelay.onStateChanged(state)
                }
            }
            tic.registerCallback(null, callback)
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            info.eventTypes = info.eventTypes or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
            service.setServiceInfo(info)
            enabled = true
            applyControllerRegion()
            handler.postDelayed({ applyControllerRegion() }, REGION_RETRY_MS)
            handler.postDelayed({ checkAvailability() }, AVAILABILITY_CHECK_MS)
            log("relay enabled slop=$touchSlop")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Could not enable live relay", t)
            runCatching { tic.unregisterAllCallbacks() }
            false
        }
    }

    private fun disable() {
        if (!enabled) return
        enabled = false
        cancelPending("relay disabled")
        handler.removeCallbacksAndMessages(null)
        runCatching { controller?.unregisterAllCallbacks() }
        runCatching {
            service.serviceInfo?.let { info ->
                info.flags = info.flags and
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
                info.eventTypes = info.eventTypes and
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_END.inv()
                service.setServiceInfo(info)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.setTouchExplorationPassthroughRegion(
                    Display.DEFAULT_DISPLAY,
                    Region()
                )
            }
        }
        log("relay disabled")
    }

    private fun checkAvailability() {
        if (!enabled || checkedAvailability) return
        checkedAvailability = true
        val manager = service.getSystemService(AccessibilityManager::class.java)
        val exploration = manager?.isTouchExplorationEnabled == true
        log("touch exploration enabled=$exploration")
        if (!exploration) {
            Log.e(TAG, "Touch exploration did not turn on; live relay unavailable")
            onUnavailable()
        }
    }

    private fun applyControllerRegion() {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            service.setTouchExplorationPassthroughRegion(
                Display.DEFAULT_DISPLAY,
                controllerRegion
            )
        }.onFailure {
            Log.w(TAG, "Could not set controller passthrough region", it)
        }
    }

    private fun onMotionEvent(event: MotionEvent) {
        if (!enabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> {
                log("cancel")
                cancelPending("touch cancelled")
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pending && !passThroughStarted) {
                    cancelPending("multi-touch")
                    try {
                        controller?.requestDelegating()
                        log("delegated multi-touch")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not delegate multi-touch", t)
                    }
                } else if (pending) {
                    log("extra pointer ignored")
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (!pending) return
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    lastX = event.getX(event.actionIndex)
                    lastY = event.getY(event.actionIndex)
                    finishPending(event.eventTime)
                }
            }
        }
    }

    private fun onDown(event: MotionEvent) {
        if (pending) {
            // The previous release did not reach us; close it before starting over.
            finishPending(event.eventTime)
        }
        val x = event.getX(0)
        val y = event.getY(0)
        if (!shouldCapture(x, y)) {
            log("down on controller, delegating")
            try {
                controller?.requestDelegating()
            } catch (t: Throwable) {
                // The passthrough region may already have delegated this touch.
                Log.d(TAG, "Delegating controller touch: ${t.message}")
            }
            return
        }
        pointerId = event.getPointerId(0)
        lastX = x
        lastY = y
        pending = true
        passThroughStarted = false
        tracker.begin(x, y, event.eventTime)
        log("down x=${x.toInt()} y=${y.toInt()} t=${event.eventTime} p=$pointerId")
    }

    private fun onMove(event: MotionEvent) {
        if (!pending) return
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        lastX = event.getX(index)
        lastY = event.getY(index)
        val crossedSlop = tracker.addPoint(lastX, lastY, event.eventTime)
        if (!crossedSlop || passThroughStarted) return
        try {
            controller?.requestDragging(pointerId)
            passThroughStarted = true
            log(
                "drag relay x=${lastX.toInt()} y=${lastY.toInt()} " +
                    "latency=${SystemClock.uptimeMillis() - event.eventTime}ms"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "requestDragging failed; gesture will be replayed on release", t)
        }
    }

    private fun onUp(event: MotionEvent) {
        if (!pending) return
        val index = event.findPointerIndex(pointerId)
        if (index >= 0) {
            lastX = event.getX(index)
            lastY = event.getY(index)
        }
        finishPending(event.eventTime)
    }

    private fun finishPending(endTimeMs: Long) {
        if (!pending) return
        pending = false
        val snapshot = tracker.finish(lastX, lastY, endTimeMs)
        val relayed = passThroughStarted
        passThroughStarted = false
        if (snapshot == null) return
        log(
            "gesture ms=${snapshot.durationMs} points=${snapshot.points.size} " +
                "relayed=$relayed start=(${snapshot.points.first().x.toInt()},${snapshot.points.first().y.toInt()})"
        )
        onGesture(snapshot)
        if (!relayed) {
            // A tap, or a drag whose live pass-through was rejected. The app has
            // not seen this gesture yet, so deliver it once now.
            injectGesture(snapshot)
        }
    }

    private fun injectGesture(snapshot: LiveGestureTracker.Snapshot) {
        val points = snapshot.points
        if (points.isEmpty()) return
        val path = Path()
        path.moveTo(points.first().x, points.first().y)
        if (MacroTimeline.maxDeviationPx(points) > touchSlop) {
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
        }
        val duration = snapshot.durationMs.coerceIn(1L, MacroTimeline.MAX_STROKE_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = try {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    log("injected gesture completed ms=$duration")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    log("injected gesture cancelled ms=$duration")
                }
            }, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Injected gesture failed", t)
            false
        }
        log("injected gesture accepted=$accepted ms=$duration")
    }

    private fun cancelPending(reason: String) {
        if (!pending) return
        pending = false
        passThroughStarted = false
        tracker.cancel()
        log("pending gesture dropped: $reason")
    }

    private fun onStateChanged(state: Int) {
        log("state ${TouchInteractionController.stateToString(state)} pending=$pending")
        if (state == TouchInteractionController.STATE_CLEAR && pending && passThroughStarted) {
            // Drag releases are not always sent as raw motion events; the state
            // transition is the last reliable end-of-gesture signal.
            finishPending(SystemClock.uptimeMillis())
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "AutoClashLiveRelay"
        private const val REGION_RETRY_MS = 150L
        private const val AVAILABILITY_CHECK_MS = 350L
    }
}
