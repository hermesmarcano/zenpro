package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.GameEvents

/** Textos cortos y sobrios para celebrar lo que pasó (barra de aviso). Sin exclamaciones en cadena ni emojis. */
object GameMessages {
    fun describe(events: GameEvents): List<String> {
        val out = mutableListOf<String>()
        if (events.mitDone) out += "Prioritaria cumplida" + if (events.xpGained > 0) " · +${events.xpGained} XP" else ""
        else if (events.xpGained > 0) out += "+${events.xpGained} XP"
        if (events.completeDay) out += "Día completo: despertaste y cumpliste lo importante"
        if (events.perfectDay) out += "Día redondo: todas las tareas hechas"
        if (events.leveledUp) out += "Nivel ${events.levelAfter}: ${Levels.title(events.levelAfter)}"
        events.unlocked.forEach { out += "Logro: ${it.title}" }
        return out
    }

    /** Lo que se dice al apagar la alarma en el ancla. */
    fun wakeSummary(events: GameEvents): List<String> = buildList {
        if (events.xpGained > 0) add("+${events.xpGained} XP")
        if (events.completeDay) add("Día completo")
        if (events.leveledUp) add("Nivel ${events.levelAfter}: ${Levels.title(events.levelAfter)}")
        events.unlocked.forEach { add("Logro: ${it.title}") }
    }
}
