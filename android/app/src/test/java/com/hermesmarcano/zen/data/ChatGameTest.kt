package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.Achievement
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.core.XpRules
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Gamificación del chat: XP una vez al día (y otro tanto por voz), logros, mensajes sobrios y nada que se pierda. */
class ChatGameTest {
    @get:Rule val tmp = TemporaryFolder()
    private val monday = LocalDate.of(2026, 11, 16)

    @Test fun theFirstConversationOfTheDayGivesXpAndTheFirstAchievement() {
        val r = GameEngine.recordChat(ZenState(), voice = false, today = monday)
        assertEquals(XpRules.CHAT_DAY, r.events.xpGained)
        assertTrue(r.events.chatDone)
        assertEquals(1, r.state.progress.chatDays)
        assertEquals(0, r.state.progress.voiceDays)
        assertEquals(XpRules.CHAT_DAY, r.state.progress.xp)
        assertEquals(listOf(Achievement.FIRST_CHAT), r.events.unlocked)
        assertEquals(listOf("Conversación del día · +5 XP", "Logro: Primera conversación"), GameMessages.describe(r.events))
    }

    @Test fun moreConversationsTheSameDayAddNothing() {
        val first = GameEngine.recordChat(ZenState(), voice = false, today = monday)
        val again = GameEngine.recordChat(first.state, voice = false, today = monday)
        assertEquals(first.state, again.state)
        assertTrue(again.events.isEmpty)
        assertTrue(GameMessages.describe(again.events).isEmpty())
    }

    @Test fun theFirstVoiceConversationOfTheDayGivesItsOwnXpOnce() {
        val text = GameEngine.recordChat(ZenState(), voice = false, today = monday)
        val voice = GameEngine.recordChat(text.state, voice = true, today = monday)
        assertEquals(XpRules.CHAT_VOICE_DAY, voice.events.xpGained)
        assertEquals(XpRules.CHAT_DAY + XpRules.CHAT_VOICE_DAY, voice.state.progress.xp)
        assertEquals(1, voice.state.progress.voiceDays)
        assertEquals(1, voice.state.progress.chatDays) // el día ya contaba
        assertTrue(Achievement.FIRST_VOICE in voice.events.unlocked)
        val again = GameEngine.recordChat(voice.state, voice = true, today = monday)
        assertEquals(voice.state, again.state)
    }

    @Test fun aVoiceConversationFirstEarnsBothAtOnce() {
        val r = GameEngine.recordChat(ZenState(), voice = true, today = monday)
        assertEquals(XpRules.CHAT_DAY + XpRules.CHAT_VOICE_DAY, r.events.xpGained)
        assertEquals(setOf(Achievement.FIRST_CHAT, Achievement.FIRST_VOICE), r.events.unlocked.toSet())
    }

    @Test fun eachNewDayCountsAgainAndSevenDaysUnlockTheHabit() {
        var state = ZenState()
        for (i in 0 until 7) {
            val r = GameEngine.recordChat(state, voice = false, today = monday.plusDays(i.toLong()))
            assertEquals(XpRules.CHAT_DAY, r.events.xpGained)
            state = r.state
        }
        assertEquals(7, state.progress.chatDays)
        assertTrue(Achievement.CHAT_DAYS_7.id in state.progress.achievements)
        assertEquals(7 * XpRules.CHAT_DAY, state.progress.xp)
    }

    @Test fun skippingDaysTakesNothingAway() {
        val a = GameEngine.recordChat(ZenState(), voice = false, today = monday)
        val b = GameEngine.recordChat(a.state, voice = false, today = monday.plusDays(30))
        assertEquals(2, b.state.progress.chatDays)
        assertEquals(2 * XpRules.CHAT_DAY, b.state.progress.xp)
    }

    @Test fun theProgressLineNeverTalksAboutLosingAnything() {
        val none = GameMessages.chatProgress(Progress(), monday)
        assertTrue("+5 XP" in none)
        val textDone = GameEngine.recordChat(ZenState(), voice = false, today = monday).state.progress
        assertTrue("por voz suma +5 XP más" in GameMessages.chatProgress(textDone, monday))
        assertTrue("Días conversando: 1 de 7" in GameMessages.chatProgress(textDone, monday))
        val both = GameEngine.recordChat(ZenState(), voice = true, today = monday).state.progress
        assertTrue("dos conversaciones" in GameMessages.chatProgress(both, monday))
        assertTrue("+5 XP" in GameMessages.chatProgress(both, monday.plusDays(1))) // otro día: vuelve a empezar, sin reproches
        for (line in listOf(none, GameMessages.chatProgress(textDone, monday), GameMessages.chatProgress(both, monday))) {
            for (bad in listOf("perder", "perderás", "racha", "no pierdas", "último")) assertFalse(bad, bad in line.lowercase())
        }
        assertFalse("de 7" in GameMessages.chatProgress(Progress(chatDays = 9), monday))
    }

    @Test fun theProgressSurvivesSavingAndLoading() {
        val file = File(tmp.root, "state.json")
        val repo = StateRepository(file)
        val state = GameEngine.recordChat(ZenState(), voice = true, today = monday).state
        repo.update { state }
        val loaded = StateRepository(file).state.value.progress
        assertEquals(state.progress.chatDays, loaded.chatDays)
        assertEquals(state.progress.voiceDays, loaded.voiceDays)
        assertEquals(monday.toEpochDay(), loaded.chatXpDay)
        assertEquals(monday.toEpochDay(), loaded.voiceXpDay)
        assertEquals(state.progress.achievements, loaded.achievements)
        // Con el estado ya cargado, el mismo día no vuelve a dar XP.
        assertTrue(GameEngine.recordChat(StateRepository(file).state.value, voice = true, today = monday).events.isEmpty)
    }

    @Test fun anOldStateFileWithoutChatFieldsStillLoads() {
        val file = File(tmp.root, "state.json")
        file.writeText("""{"version":1,"progress":{"xp":120}}""")
        val progress = StateRepository(file).state.value.progress
        assertEquals(0, progress.chatDays)
        assertEquals(null, progress.chatXpDay)
        assertEquals(120, progress.xp)
    }
}
