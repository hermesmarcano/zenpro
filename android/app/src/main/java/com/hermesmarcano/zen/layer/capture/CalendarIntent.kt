package com.hermesmarcano.zen.layer.capture

import android.content.Intent
import android.provider.CalendarContract

/**
 * El intent que abre el calendario con un evento ya relleno (`ACTION_INSERT`): la persona lo revisa y lo guarda ella.
 * No lee ni escribe el calendario, así que la app no necesita ningún permiso suyo.
 */
object CalendarIntent {
    fun insert(title: String, startMs: Long, endMs: Long): Intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
}
