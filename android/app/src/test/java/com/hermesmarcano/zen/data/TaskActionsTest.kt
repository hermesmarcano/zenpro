package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.TaskBoard
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskActionsTest {
    private val file = File(Files.createTempDirectory("zen-tasks").toFile(), "state.json")
    private val repo = StateRepository(file)
    private val today = LocalDate.of(2026, 3, 4)
    private val day = today.toEpochDay()

    private fun add(title: String, d: Long = day) = TaskActions.add(repo, d, title)
    private fun titles(d: Long = day) = TaskBoard.forDay(repo.state.value.tasks, d).map { it.title }

    @Test fun addsPersistAndSurviveARestart() {
        assertTrue(add("Primera") is TaskBoard.AddResult.Added)
        add("Segunda")
        assertEquals(listOf("Primera", "Segunda"), titles())
        assertEquals(listOf("Primera", "Segunda"), TaskBoard.forDay(StateRepository(file).state.value.tasks, day).map { it.title })
    }

    @Test fun rejectedAddsLeaveTheStateUntouched() {
        repeat(10) { add("t$it") }
        assertEquals(TaskBoard.AddResult.Full, add("otra"))
        assertEquals(TaskBoard.AddResult.Blank, add("   "))
        assertEquals(10, titles().size)
    }

    @Test fun completingGivesXpAndPersistsIt() {
        add("Importante")
        val id = repo.state.value.tasks.single().id
        val events = TaskActions.setDone(repo, id, true, today)
        assertEquals(30, events.xpGained)
        assertTrue(StateRepository(file).state.value.tasks.single().done)
        assertEquals(30, StateRepository(file).state.value.progress.xp)
    }

    @Test fun deleteThenUndoRestoresThePosition() {
        add("A"); add("B"); add("C")
        val idB = repo.state.value.tasks.first { it.title == "B" }.id
        val deleted = TaskActions.delete(repo, idB)!!
        assertEquals(listOf("A", "C"), titles())
        assertTrue(TaskActions.undoDelete(repo, deleted))
        assertEquals(listOf("A", "B", "C"), titles())
    }

    @Test fun undoReportsWhenTheListFilledUp() {
        repeat(10) { add("t$it") }
        val deleted = TaskActions.delete(repo, repo.state.value.tasks.first().id)!!
        add("nueva")
        assertFalse(TaskActions.undoDelete(repo, deleted))
        assertEquals(10, titles().size)
    }

    @Test fun moveRenameAndCarryOver() {
        add("A"); add("B"); add("C")
        TaskActions.move(repo, day, 2, 0)
        assertEquals(listOf("C", "A", "B"), titles())
        TaskActions.rename(repo, repo.state.value.tasks.first { it.title == "A" }.id, "  Renombrada ")
        assertEquals(listOf("C", "Renombrada", "B"), titles())
        add("ayer-1", day - 1); add("ayer-2", day - 1)
        val result = TaskActions.carryOver(repo, today)
        assertEquals(2, result.moved)
        assertEquals(listOf("C", "Renombrada", "B", "ayer-1", "ayer-2"), titles())
        assertEquals(0, TaskActions.carryOver(repo, today).moved)
    }

    @Test fun deletingAnUnknownIdReturnsNull() {
        assertEquals(null, TaskActions.delete(repo, "nope"))
        assertNotNull(repo.state.value)
    }
}
