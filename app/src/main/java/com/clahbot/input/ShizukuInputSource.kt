package com.clahbot.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.clahbot.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuInputSource(context: Context) : InputEventSource {

    override val label: String = "Shizuku"

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
    fun ensureBound(timeoutMs: Long = 8_000L): Boolean {
        if (service != null) return true
        if (!shizukuReady()) return false

        val latch = CountDownLatch(1)
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.let { IInputEventService.Stub.asInterface(it) }
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
        } catch (_: Throwable) {
            connection = null
            return false
        }

        val bound = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!bound || service == null) {
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
            service?.getTouchscreenInfo()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    override fun start(
        devicePath: String,
        onLines: (String) -> Unit,
        onFatal: (String) -> Unit
    ): Boolean {
        if (!DEVICE_PATH.matches(devicePath)) {
            onFatal("Invalid touchscreen path")
            return false
        }
        if (!ensureBound()) {
            onFatal("Shizuku input service is unavailable")
            return false
        }

        val newCallback = object : IInputEventCallback.Stub() {
            override fun onLines(lines: String) {
                onLines(lines)
            }

            override fun onError(message: String) {
                onFatal(message)
            }
        }
        fatal = onFatal
        callback = newCallback
        streaming = true
        return try {
            val current = service
            if (current == null) {
                streaming = false
                onFatal("Shizuku input service is unavailable")
                false
            } else {
                current.start(devicePath, newCallback)
                true
            }
        } catch (t: Throwable) {
            streaming = false
            fatal = null
            callback = null
            onFatal("Could not start Shizuku input: ${t.message ?: "binder error"}")
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

    private fun shizukuReady(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val SERVICE_VERSION = 1
        private val DEVICE_PATH = Regex("""^/dev/input/event\d+$""")
    }
}
