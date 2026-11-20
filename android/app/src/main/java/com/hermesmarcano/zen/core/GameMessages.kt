package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.GameEvents
import com.hermesmarcano.zen.data.Progress
import java.time.LocalDate

/** Textos cortos y sobrios para celebrar lo que pasó (barra de aviso). Sin exclamaciones en cadena ni emojis. */
object GameMessages {
    fun describe(events: GameEvents): List<String> {
        val out = mutableListOf<String>()
        if (events.mitDone) out += "Prioritaria cumplida" + if (events.xpGained > 0) " · +${events.xpGained} XP" else ""
        else if (events.xpGained > 0 && !events.workoutDone && !events.chatDone) out += "+${events.xpGained} XP"
        if (events.workoutDone) out += "Entrenamiento completado" + if (events.xpGained > 0) " · +${events.xpGained} XP" else ""
        if (events.chatDone) out += "Conversación del día" + if (events.xpGained > 0) " · +${events.xpGained} XP" else ""
        if (events.records > 0) out += if (events.records == 1) "Marca personal en 1 ejercicio" else "Marcas personales en ${events.records} ejercicios"
        if (events.weeklyGoalMet) out += "Objetivo semanal cumplido"
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

    /**
     * Cómo va el hábito de hablar con el cerebro, en una línea (para el chat). Dice qué suma y cuánto falta para el próximo logro;
     * nunca avisa de lo que se «pierde»: un día sin hablar no quita nada.
     */
    fun chatProgress(progress: Progress, today: LocalDate): String {
        val day = today.toEpochDay()
        val chat = progress.chatXpDay == day
        val voice = progress.voiceXpDay == day
        val head = when {
            chat && voice -> "Hoy ya sumaste tus dos conversaciones."
            chat -> "Conversación de hoy hecha. Una por voz suma +${XpRules.CHAT_VOICE_DAY} XP más."
            else -> "Una conversación de hoy (3 palabras o más) suma +${XpRules.CHAT_DAY} XP; por voz, +${XpRules.CHAT_VOICE_DAY} más."
        }
        val days = progress.chatDays
        val tail = when {
            days <= 0 -> ""
            days >= 7 -> " Días conversando: $days."
            else -> " Días conversando: $days de 7 para el próximo logro."
        }
        return head + tail
    }
}
