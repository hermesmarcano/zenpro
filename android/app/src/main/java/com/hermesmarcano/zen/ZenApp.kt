package com.hermesmarcano.zen

import android.app.Application
import android.content.Context
import com.hermesmarcano.zen.alarm.Notifications
import com.hermesmarcano.zen.data.StateRepository
import com.hermesmarcano.zen.photo.PhotoStore
import com.hermesmarcano.zen.sound.SoundStorage
import java.io.File

class ZenApp : Application() {
    lateinit var repository: StateRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = StateRepository(File(filesDir, "state.json"))
        Notifications.createChannels(this)
        // Fotos de referencia de anclas que nunca se confirmaron (la app se cerró a mitad del alta).
        PhotoStore.deleteOrphans(this, repository.state.value)
        SoundStorage.deleteOrphans(this, repository.state.value)
    }
}

val Context.zenApp: ZenApp get() = applicationContext as ZenApp
