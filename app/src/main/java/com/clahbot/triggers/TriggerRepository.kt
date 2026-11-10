package com.clahbot.triggers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The whole image-trigger file; stored in triggers.json. */
data class TriggersFile(
    @SerializedName("triggers") val triggers: List<ImageTrigger> = emptyList()
)

/**
 * Persists image-trigger metadata (triggers.json) and their reference PNGs
 * (image_triggers/<id>.png). Same atomic-write + corrupt-quarantine pattern as
 * RecoveryRepository, so a bad file is preserved aside instead of overwritten.
 */
class TriggerRepository internal constructor(
    private val file: File,
    private val dir: File
) {

    constructor(context: Context) : this(
        File(context.filesDir, FILE_NAME),
        File(context.filesDir, IMAGE_DIR)
    )

    private val gson = Gson()

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<TriggersFile> = mutable

    private fun load(): TriggersFile {
        if (!file.exists()) return TriggersFile()
        val parsed = runCatching { gson.fromJson(file.readText(), TriggersFile::class.java) }
            .getOrElse {
                quarantine()
                return TriggersFile()
            }
        if (parsed == null) {
            quarantine()
            return TriggersFile()
        }
        return parsed
    }

    @Synchronized
    fun refresh(): TriggersFile {
        val loaded = load()
        mutable.value = loaded
        return loaded
    }

    private fun quarantine() {
        try {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
        } catch (_: Exception) {
            // An unreadable file is treated as empty; nothing else to preserve.
        }
    }

    private fun update(transform: (TriggersFile) -> TriggersFile) {
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

    fun all(): List<ImageTrigger> = mutable.value.triggers

    fun saveTrigger(trigger: ImageTrigger) = update { file ->
        file.copy(triggers = file.triggers.filterNot { it.id == trigger.id } + trigger)
    }

    fun deleteTrigger(id: String) = update { file ->
        file.copy(triggers = file.triggers.filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) = updateTrigger(id) { it.copy(enabled = enabled) }

    fun setPriority(id: String, priority: Int) = updateTrigger(id) { it.copy(priority = priority) }

    private fun updateTrigger(id: String, transform: (ImageTrigger) -> ImageTrigger) = update { file ->
        file.copy(triggers = file.triggers.map { if (it.id == id) transform(it) else it })
    }

    /** Records a match result so the UI can show the last outcome. */
    fun noteMatch(id: String, result: String, at: Long = System.currentTimeMillis()) =
        updateTrigger(id) {
            it.copy(lastMatchedAt = at, lastResult = result, matchCount = it.matchCount + 1)
        }

    fun imageFile(trigger: ImageTrigger): File = File(dir, trigger.imageFile)

    /** Stores a reference PNG and returns the file name to put in the trigger. */
    fun storeImage(id: String, bytes: ByteArray): String {
        dir.mkdirs()
        val name = "$id.png"
        File(dir, name).writeBytes(bytes)
        return name
    }

    companion object {
        private const val FILE_NAME = "triggers.json"
        private const val IMAGE_DIR = "image_triggers"

        @Volatile
        private var instance: TriggerRepository? = null

        fun get(context: Context): TriggerRepository =
            instance ?: synchronized(this) {
                instance ?: TriggerRepository(context.applicationContext).also { instance = it }
            }
    }
}
