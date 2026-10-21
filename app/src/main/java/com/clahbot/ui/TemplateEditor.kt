package com.clahbot.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.clahbot.data.db.AppDatabase
import com.clahbot.data.db.TemplateEntity
import java.io.File
import java.io.FileOutputStream

class TemplateEditor(private val db: AppDatabase, private val filesDir: File) {

    suspend fun saveTemplate(name: String, label: String, bitmap: Bitmap, region: Region? = null): Long {
        val dir = File(filesDir, "templates").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}_$name.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return db.templateDao().insert(
            TemplateEntity(
                name = name,
                label = label,
                imagePath = file.absolutePath,
                regionX = region?.x ?: 0,
                regionY = region?.y ?: 0,
                regionW = region?.w ?: bitmap.width,
                regionH = region?.h ?: bitmap.height
            )
        )
    }

    suspend fun loadTemplates(): List<TemplateEntity> = db.templateDao().getAll()

    fun loadBitmap(entity: TemplateEntity): Bitmap? {
        val file = File(entity.imagePath)
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    suspend fun deleteTemplate(id: Long) {
        db.templateDao().delete(id)
    }

    data class Region(val x: Int, val y: Int, val w: Int, val h: Int)
}
