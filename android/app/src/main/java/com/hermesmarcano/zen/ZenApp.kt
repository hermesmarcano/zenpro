package com.hermesmarcano.zen

import android.app.Application
import android.content.Context
import com.hermesmarcano.zen.alarm.Notifications
import com.hermesmarcano.zen.alarm.RestTimer
import com.hermesmarcano.zen.data.StateRepository
import com.hermesmarcano.zen.data.WorkoutRepository
import com.hermesmarcano.zen.photo.PhotoStore
import com.hermesmarcano.zen.sound.SoundStorage
import com.hermesmarcano.zen.core.TaskBoard
import com.hermesmarcano.zen.core.TripPolicy
import java.io.File
import java.io.IOException
import java.time.LocalDate

class ZenApp : Application() {
    lateinit var repository: StateRepository
        private set

    lateinit var workouts: WorkoutRepository
        private set

    /** Olvida tareas de hace más de una semana y un viaje que ya terminó. Nunca bloquea el arranque. */
    private fun tidyState() {
        try {
            val today = LocalDate.now().toEpochDay()
            repository.update { state ->
                state.copy(
                    tasks = TaskBoard.prune(state.tasks, today),
                    trip = state.trip?.takeIf { TripPolicy.isActive(it, today) },
                )
            }
        } catch (_: IOException) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = StateRepository(File(filesDir, "state.json"))
        workouts = WorkoutRepository(File(filesDir, "workouts.json"), File(filesDir, "active_workout.json"))
        Notifications.createChannels(this)
        RestTimer.createChannels(this)
        tidyState()
        // Fotos de referencia de anclas que nunca se confirmaron (la app se cerró a mitad del alta).
        PhotoStore.deleteOrphans(this, repository.state.value)
        SoundStorage.deleteOrphans(this, repository.state.value)
    }
}

val Context.zenApp: ZenApp get() = applicationContext as ZenApp