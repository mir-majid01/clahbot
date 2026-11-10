package com.clahbot.triggers

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TriggerRepositoryTest {

    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var imageDir: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-triggers").toFile()
        file = File(directory, "triggers.json")
        imageDir = File(directory, "image_triggers")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun repository() = TriggerRepository(file, imageDir)

    private fun trigger(
        id: String = "t1",
        enabled: Boolean = true,
        priority: Int = 0
    ) = ImageTrigger(
        id = id,
        name = "Trigger $id",
        imageFile = "$id.png",
        refWidth = 64,
        refHeight = 40,
        threshold = 0.9f,
        action = TriggerAction(
            type = TriggerActionType.SWIPE,
            point = TriggerPoint(0.2f, 0.3f, relativeToMatch = true),
            endPoint = TriggerPoint(0.6f, 0.7f, relativeToMatch = true),
            durationMs = 250,
            repeat = 2,
            repeatGapMs = 100
        ),
        enabled = enabled,
        priority = priority
    )

    @Test
    fun `a saved trigger and its nested action survive a reload`() {
        repository().saveTrigger(trigger())

        val reloaded = repository()
        reloaded.refresh()
        val saved = reloaded.all().single()
        assertEquals("t1", saved.id)
        assertEquals(0.9f, saved.threshold, 0.0001f)
        assertEquals(TriggerActionType.SWIPE, saved.action.type)
        assertEquals(0.2f, saved.action.point!!.x, 0.0001f)
        assertEquals(0.6f, saved.action.endPoint!!.x, 0.0001f)
        assertEquals(250, saved.action.durationMs)
        assertEquals(2, saved.action.repeat)
        assertTrue(saved.enabled)
    }

    @Test
    fun `enable, priority and delete persist`() {
        val repository = repository()
        repository.saveTrigger(trigger())

        repository.setEnabled("t1", false)
        repository.setPriority("t1", 9)

        val reloaded = repository()
        reloaded.refresh()
        val saved = reloaded.all().single()
        assertFalse(saved.enabled)
        assertEquals(9, saved.priority)

        reloaded.deleteTrigger("t1")
        val erased = repository()
        erased.refresh()
        assertTrue(erased.all().isEmpty())
    }

    @Test
    fun `recording a match stores the result and count`() {
        val repository = repository()
        repository.saveTrigger(trigger())
        repository.noteMatch("t1", "acted: tap", at = 4321L)

        val reloaded = repository()
        reloaded.refresh()
        val saved = reloaded.all().single()
        assertEquals(4321L, saved.lastMatchedAt)
        assertEquals("acted: tap", saved.lastResult)
        assertEquals(1, saved.matchCount)
    }

    @Test
    fun `storing an image writes it under image_triggers`() {
        val repository = repository()
        val name = repository.storeImage("t1", byteArrayOf(1, 2, 3, 4))
        assertEquals("t1.png", name)
        val stored = File(imageDir, name)
        assertTrue(stored.exists())
        assertEquals(4, stored.length())
    }

    @Test
    fun `an unreadable file is quarantined and does not crash`() {
        file.writeText("{ not json")
        val repository = repository()
        repository.refresh()
        assertTrue(repository.all().isEmpty())
        val quarantined = directory.listFiles()?.any { it.name.contains("corrupt") } ?: false
        assertTrue("the corrupt triggers file was not preserved", quarantined)
    }

    @Test
    fun `an absent file loads as empty`() {
        val repository = repository()
        assertTrue(repository.all().isEmpty())
        assertNull(directory.listFiles()?.firstOrNull { it.name == "triggers.json" })
    }

    @Test
    fun `the image file path resolves under the image directory`() {
        val repository = repository()
        val stored = repository.imageFile(trigger())
        assertNotNull(stored)
        assertEquals(imageDir.absolutePath, stored.parentFile?.absolutePath)
    }
}
