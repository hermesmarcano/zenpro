package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.TaskBoard
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** Acciones sobre las tareas con persistencia. Cada una es una sola transacción sobre el estado (atómica). */
object TaskActions {
    @Throws(IOException::class)
    fun add(repository: StateRepository, day: Long, title: String): TaskBoard.AddResult {
        var outcome: TaskBoard.AddResult = TaskBoard.AddResult.Blank
        repository.update { state ->
            val result = TaskBoard.add(state.tasks, day, title, UUID.randomUUID().toString())
            outcome = result
            if (result is TaskBoard.AddResult.Added) state.copy(tasks = result.tasks) else state
        }
        return outcome
    }

    /** Marca/desmarca una tarea de hoy y devuelve lo que el juego quiera celebrar. */
    @Throws(IOException::class)
    fun setDone(repository: StateRepository, id: String, done: Boolean, today: LocalDate): GameEvents {
        var events = GameEvents()
        repository.update { state ->
            val result = GameEngine.setTaskDone(state, id, done, today)
            events = result.events
            result.state
        }
        return events
    }

    @Throws(IOException::class)
    fun move(repository: StateRepository, day: Long, from: Int, to: Int) {
        repository.update { it.copy(tasks = TaskBoard.move(it.tasks, day, from, to)) }
    }

    @Throws(IOException::class)
    fun delete(repository: StateRepository, id: String): TaskBoard.Deleted? {
        var deleted: TaskBoard.Deleted? = null
        repository.update { state ->
            val (tasks, removed) = TaskBoard.delete(state.tasks, id)
            deleted = removed
            state.copy(tasks = tasks)
        }
        return deleted
    }

    /** Deshace un borrado; `false` si ya no cabe (el día se llenó). */
    @Throws(IOException::class)
    fun undoDelete(repository: StateRepository, deleted: TaskBoard.Deleted): Boolean {
        var ok = false
        repository.update { state ->
            val restored = TaskBoard.restore(state.tasks, deleted)
            ok = restored != null
            if (restored != null) state.copy(tasks = restored) else state
        }
        return ok
    }

    @Throws(IOException::class)
    fun rename(repository: StateRepository, id: String, title: String) {
        repository.update { it.copy(tasks = TaskBoard.rename(it.tasks, id, title)) }
    }

    /** Trae a hoy las pendientes de días anteriores. */
    @Throws(IOException::class)
    fun carryOver(repository: StateRepository, today: LocalDate): TaskBoard.CarryResult {
        var result = TaskBoard.CarryResult(emptyList(), 0, 0)
        repository.update { state ->
            result = TaskBoard.carryOver(state.tasks, today.toEpochDay())
            if (result.moved > 0) state.copy(tasks = result.tasks) else state
        }
        return result
    }
}
