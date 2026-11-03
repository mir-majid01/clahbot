package com.clahbot.walls

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WallFile(
    @SerializedName("settings") val settings: WallSettings = WallSettings(),
    @SerializedName("spendDate") val spendDate: String = "",
    @SerializedName("spendToday") val spendToday: Long = 0
)

/**
 * Persists wall settings, line/camera setup, cycle history and the daily
 * spend total. The per-session cap is intentionally not persisted.
 */
class WallsRepository private constructor(context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, FILE_NAME)
    private val dir = File(context.filesDir, ASSET_DIR)

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<WallFile> = mutable

    @Volatile
    var spendSession: Long = 0
        private set

    private fun load(): WallFile = runCatching {
        if (file.exists()) {
            gson.fromJson(file.readText(), WallFile::class.java)
        } else {
            null
        }
    }.getOrNull() ?: WallFile()

    @Synchronized
    fun refresh(): WallFile {
        mutable.value = load()
        return mutable.value
    }

    @Synchronized
    private fun update(transform: (WallFile) -> WallFile) {
        val next = transform(mutable.value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(gson.toJson(next))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(next))
                tmp.delete()
            }
        }
        mutable.value = next
    }

    fun updateSettings(transform: (WallSettings) -> WallSettings) =
        update { it.copy(settings = transform(it.settings)) }

    fun settings(): WallSettings = mutable.value.settings

    fun saveLandmarkPatch(bitmap: Bitmap): WallCamera? {
        val name = "landmark.png"
        runCatching {
            dir.mkdirs()
            File(dir, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.onFailure { return null }
        return WallCamera(
            landmarkLeft = 0f,
            landmarkTop = 0f,
            landmarkRight = 0f,
            landmarkBottom = 0f,
            patchFile = name
        )
    }

    fun landmarkPatch(camera: WallCamera?): Bitmap? {
        val name = camera?.patchFile ?: return null
        val file = File(dir, name)
        if (!file.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun noteSpend(amount: Long) {
        if (amount <= 0) return
        spendSession += amount
        update { current ->
            val today = SimpleDateFormat(DAY_FORMAT, Locale.US).format(Date())
            val carried = if (current.spendDate == today) current.spendToday else 0L
            current.copy(spendDate = today, spendToday = carried + amount)
        }
    }

    fun spendToday(): Long {
        val today = SimpleDateFormat(DAY_FORMAT, Locale.US).format(Date())
        val current = mutable.value
        return if (current.spendDate == today) current.spendToday else 0L
    }

    fun recordCycle(log: WallCycleLog) = update {
        it.copy(settings = it.settings.copy(history = (it.settings.history + log).takeLast(HISTORY_MAX)))
    }

    fun clearHistory() = update { it.copy(settings = it.settings.copy(history = emptyList())) }

    companion object {
        private const val FILE_NAME = "walls.json"
        private const val ASSET_DIR = "walls"
        private const val HISTORY_MAX = 50
        private const val DAY_FORMAT = "yyyy-MM-dd"

        @Volatile
        private var instance: WallsRepository? = null

        fun get(context: Context): WallsRepository =
            instance ?: synchronized(this) {
                instance ?: WallsRepository(context.applicationContext).also { instance = it }
            }
    }
}
