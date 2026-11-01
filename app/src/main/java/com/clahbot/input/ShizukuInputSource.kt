package com.clahbot.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.clahbot.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuInputSource(context: Context) : InputEventSource {

    override val label: String = "Shizuku"

    @Volatile
    override var lastFailure: String? = null

    private val appContext = context.applicationContext
    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext, ShizukuInputService::class.java)
    )
        .daemon(false)
        .processNameSuffix("input")
        .debuggable(BuildConfig.DEBUG)
        .version(SERVICE_VERSION)

    @Volatile
    private var service: IInputEventService? = null

    @Volatile
    private var streaming = false

    @Volatile
    private var fatal: ((String) -> Unit)? = null

    private var connection: ServiceConnection? = null
    private var callback: IInputEventCallback.Stub? = null

    @Synchronized
    fun ensureBound(timeoutMs: Long = BIND_TIMEOUT_MS): Boolean {
        if (service != null) return true
        val state = shizukuState()
        Log.i(
            TAG,
            "Shizuku binder=${runCatching { Shizuku.pingBinder() }.getOrDefault(false)} " +
                "version=${runCatching { Shizuku.getVersion() }.getOrNull()} " +
                "permission=${runCatching { Shizuku.checkSelfPermission() }.getOrNull()} " +
                "state=$state"
        )
        if (state != ShizukuState.READY) {
            lastFailure = state.message
            return false
        }

        repeat(BIND_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "Retrying Shizuku user service bind (attempt ${attempt + 1})")
                Thread.sleep(BIND_RETRY_DELAY_MS)
            }
            if (bindOnce(timeoutMs)) {
                Log.i(TAG, "Shizuku user service connected")
                lastFailure = null
                return true
            }
        }
        lastFailure =
            "Shizuku did not start its input service. Open Shizuku, restart it, then try again."
        return false
    }

    private fun bindOnce(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (abandoned.get()) return
                val proxy = binder?.let { IInputEventService.Stub.asInterface(it) }
                if (proxy == null) {
                    latch.countDown()
                    return
                }
                service = proxy
                accepted.set(true)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                if (streaming) fatal?.invoke("Shizuku input service disconnected")
            }
        }
        connection = newConnection
        try {
            Shizuku.bindUserService(args, newConnection)
        } catch (t: Throwable) {
            connection = null
            lastFailure = "Shizuku bind failed: ${t.message ?: "binder error"}"
            return false
        }

        val bound = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!bound || !accepted.get() || service == null) {
            abandoned.set(true)
            service = null
            Log.w(TAG, "Shizuku user service bind timed out after ${timeoutMs}ms")
            connection = null
            try {
                Shizuku.unbindUserService(args, newConnection, true)
            } catch (_: Throwable) {
                // The binder is already gone.
            }
            return false
        }
        return true
    }

    override fun discover(): String? {
        if (!ensureBound()) return null
        return try {
            val info = service?.getTouchscreenInfo()
            if (info.isNullOrBlank()) {
                lastFailure = "Shizuku could not list input devices."
                null
            } else {
                lastFailure = null
                info
            }
        } catch (t: Throwable) {
            lastFailure = "Shizuku input service failed: ${t.message ?: "binder error"}"
            null
        }
    }

    override fun start(
        devicePath: String,
        onLines: (String) -> Unit,
        onFatal: (String) -> Unit
    ): Boolean {
        if (!DEVICE_PATH.matches(devicePath)) {
            lastFailure = "Shizuku touchscreen path is invalid."
            onFatal(lastFailure!!)
            return false
        }
        if (!ensureBound()) {
            onFatal(lastFailure ?: "Shizuku input service is unavailable")
            return false
        }

        val firstBatch = AtomicBoolean(false)
        val newCallback = object : IInputEventCallback.Stub() {
            override fun onLines(lines: String) {
                if (firstBatch.compareAndSet(false, true)) {
                    Log.i(TAG, "First getevent batch received (${lines.length} chars)")
                }
                onLines(lines)
            }

            override fun onError(message: String) {
                onFatal(message)
            }
        }
        Log.i(TAG, "Starting Shizuku getevent for $devicePath")
        fatal = onFatal
        callback = newCallback
        streaming = true
        return try {
            val current = service
            if (current == null) {
                streaming = false
                lastFailure = "Shizuku input service is unavailable."
                onFatal(lastFailure!!)
                false
            } else {
                current.start(devicePath, newCallback)
                true
            }
        } catch (t: Throwable) {
            streaming = false
            fatal = null
            callback = null
            lastFailure = "Could not start Shizuku input: ${t.message ?: "binder error"}"
            onFatal(lastFailure!!)
            false
        }
    }

    override fun stop() {
        streaming = false
        fatal = null
        callback = null
        try {
            service?.stop()
        } catch (_: Throwable) {
            // The service process may already be gone.
        }
    }

    override fun close() {
        stop()
        val bound = connection
        connection = null
        service = null
        if (bound != null) {
            try {
                Shizuku.unbindUserService(args, bound, true)
            } catch (_: Throwable) {
                // The service process may already be gone.
            }
        }
    }

    private fun shizukuState(): ShizukuState {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuState.NOT_RUNNING
            } else if (Shizuku.isPreV11()) {
                ShizukuState.TOO_OLD
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                ShizukuState.NO_PERMISSION
            } else {
                ShizukuState.READY
            }
        } catch (t: Throwable) {
            ShizukuState.ERROR
        }
    }

    private enum class ShizukuState(val message: String?) {
        READY(null),
        NOT_RUNNING("Shizuku is not running. Start Shizuku, then try again."),
        TOO_OLD("Shizuku version is too old. Update Shizuku, then try again."),
        NO_PERMISSION("Shizuku access is not granted. Grant it in Settings, then try again."),
        ERROR("Shizuku is unavailable. Restart it, then try again.")
    }

    companion object {
        const val SERVICE_VERSION = 1
        private const val TAG = "AutoClashInput"
        private const val BIND_TIMEOUT_MS = 4_000L
        private const val BIND_ATTEMPTS = 2
        private const val BIND_RETRY_DELAY_MS = 350L
        private val DEVICE_PATH = Regex("""^/dev/input/event\d+$""")
    }
}
