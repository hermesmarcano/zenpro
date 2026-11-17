package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.AlarmPlanner
import com.hermesmarcano.zen.data.DayRange
import com.hermesmarcano.zen.data.GameEngine
import com.hermesmarcano.zen.data.Progress
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.TaskItem
import com.hermesmarcano.zen.data.Trip
import com.hermesmarcano.zen.data.WakeStats
import com.hermesmarcano.zen.data.ZenState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {
    private val day = 20_000L

    private fun board(vararg titles: String, forDay: Long = day): List<TaskItem> {
        var all = emptyList<TaskItem>()
        titles.forEachIndexed { i, t -> all = (TaskBoard.add(all, forDay, t, "id-$forDay-$i") as TaskBoard.AddResult.Added).tasks }
        return all
    }

    private fun titles(all: List<TaskItem>, forDay: Long = day) = TaskBoard.forDay(all, forDay).map { it.title }

    @Test fun priorityComesFromPosition() {
        assertEquals(TaskBoard.Tier.HIGH, TaskBoard.tierAt(0))
        assertEquals(TaskBoard.Tier.MEDIUM, TaskBoard.tierAt(1))
        assertEquals(TaskBoard.Tier.MEDIUM, TaskBoard.tierAt(3))
        assertEquals(TaskBoard.Tier.LOW, TaskBoard.tierAt(4))
        assertEquals(TaskBoard.Tier.LOW, TaskBoard.tierAt(9))
        assertEquals(1 + 3 + 6, TaskBoard.MAX_PER_DAY)
    }

    @Test fun newTasksFillFromTheTopAndReportTheirTier() {
        var all = emptyList<TaskItem>()
        val tiers = (0 until 10).map { i ->
            val r = TaskBoard.add(all, day, "t$i", "id$i") as TaskBoard.AddResult.Added
            all = r.tasks
            r.tier
        }
        assertEquals(1, tiers.count { it == TaskBoard.Tier.HIGH })
        assertEquals(3, tiers.count { it == TaskBoard.Tier.MEDIUM })
        assertEquals(6, tiers.count { it == TaskBoard.Tier.LOW })
    }

    @Test fun theEleventhTaskIsRefused() {
        val all = board(*Array(10) { "t$it" })
        assertEquals(TaskBoard.AddResult.Full, TaskBoard.add(all, day, "una más", "x"))
        // Otro día tiene su propio cupo.
        assertTrue(TaskBoard.add(all, day + 1, "mañana", "y") is TaskBoard.AddResult.Added)
    }

    @Test fun titlesAreCleanedAndValidated() {
        assertEquals(TaskBoard.AddResult.Blank, TaskBoard.add(emptyList(), day, "   \n ", "a"))
        assertEquals(TaskBoard.AddResult.TooLong, TaskBoard.add(emptyList(), day, "x".repeat(81), "a"))
        val ok = TaskBoard.add(emptyList(), day, "  escribir   el   informe ", "a") as TaskBoard.AddResult.Added
        assertEquals("escribir el informe", ok.item.title)
        assertTrue(TaskBoard.add(emptyList(), day, "x".repeat(80), "a") is TaskBoard.AddResult.Added)
    }

    @Test fun draggingChangesThePriority() {
        var all = board("A", "B", "C", "D", "E")
        assertEquals(TaskBoard.Tier.LOW, TaskBoard.tierAt(titles(all).indexOf("E")))
        all = TaskBoard.move(all, day, from = 4, to = 0)
        assertEquals(listOf("E", "A", "B", "C", "D"), titles(all))
        assertEquals(TaskBoard.Tier.HIGH, TaskBoard.tierAt(titles(all).indexOf("E")))
        assertEquals(TaskBoard.Tier.LOW, TaskBoard.tierAt(titles(all).indexOf("D")))
        assertEquals(listOf(0, 1, 2, 3, 4), TaskBoard.forDay(all, day).map { it.position })
    }

    @Test fun moveIgnoresBadIndicesAndSameSpot() {
        val all = board("A", "B", "C")
        assertEquals(all, TaskBoard.move(all, day, 0, 0))
        assertEquals(all, TaskBoard.move(all, day, -1, 1))
        assertEquals(all, TaskBoard.move(all, day, 0, 3))
        assertEquals(all, TaskBoard.move(all, day + 5, 0, 1))
    }

    @Test fun movingOneDayDoesNotTouchAnother() {
        val all = board("A", "B", forDay = day) + board("X", "Y", forDay = day + 1)
        val moved = TaskBoard.move(all, day, 0, 1)
        assertEquals(listOf("X", "Y"), titles(moved, day + 1))
        assertEquals(listOf("B", "A"), titles(moved))
    }

    @Test fun completingIsIndependentOfPosition() {
        var all = board("A", "B", "C")
        all = TaskBoard.setDone(all, "id-$day-0", true)
        all = TaskBoard.move(all, day, 0, 2)
        assertEquals(listOf("B", "C", "A"), titles(all))
        assertTrue(TaskBoard.forDay(all, day).last().done)
        assertEquals(1, TaskBoard.forDay(all, day).count { it.done })
    }

    @Test fun deletingRenumbersAndCanBeUndone() {
        val all = board("A", "B", "C", "D")
        val (after, deleted) = TaskBoard.delete(all, "id-$day-1")
        assertNotNull(deleted)
        assertEquals(listOf("A", "C", "D"), titles(after))
        assertEquals(listOf(0, 1, 2), TaskBoard.forDay(after, day).map { it.position })
        val restored = TaskBoard.restore(after, deleted!!)!!
        assertEquals(listOf("A", "B", "C", "D"), titles(restored))
    }

    @Test fun undoFailsWhenTheDayFilledUpOrTheTaskIsBack() {
        val all = board(*Array(10) { "t$it" })
        val (after, deleted) = TaskBoard.delete(all, "id-$day-3")
        val filled = (TaskBoard.add(after, day, "nueva", "n") as TaskBoard.AddResult.Added).tasks
        assertNull(TaskBoard.restore(filled, deleted!!))
        assertNull(TaskBoard.restore(all, deleted)) // ya está
    }

    @Test fun deletingAnUnknownTaskChangesNothing() {
        val all = board("A")
        val (after, deleted) = TaskBoard.delete(all, "nope")
        assertEquals(all, after); assertNull(deleted)
    }

    @Test fun renameTrimsAndRejectsBadTitles() {
        val all = board("A")
        assertEquals("Nuevo nombre", TaskBoard.rename(all, "id-$day-0", "  Nuevo   nombre ").single().title)
        assertEquals("A", TaskBoard.rename(all, "id-$day-0", "  ").single().title)
        assertEquals("A", TaskBoard.rename(all, "id-$day-0", "x".repeat(81)).single().title)
    }

    @Test fun carryOverBringsPendingTasksUpToTheLimit() {
        val yesterday = board("p1", "p2", "hecha", forDay = day - 1).let { TaskBoard.setDone(it, "id-${day - 1}-2", true) }
        val today = board("hoy1", forDay = day)
        val all = yesterday + today
        assertEquals(2, TaskBoard.pendingBefore(all, day).size)
        val result = TaskBoard.carryOver(all, day)
        assertEquals(2, result.moved); assertEquals(0, result.left)
        assertEquals(listOf("hoy1", "p1", "p2"), titles(result.tasks))
        assertEquals(listOf("hecha"), titles(result.tasks, day - 1)) // lo hecho se queda
    }

    @Test fun carryOverKeepsTheOriginalOrderWhateverTheIds() {
        // Ids en orden inverso al de las posiciones: un desempate por id invertiría el resultado.
        val yesterday = listOf(
            TaskItem("z", "primera", false, day - 1, 0),
            TaskItem("m", "segunda", false, day - 1, 1),
            TaskItem("a", "tercera", false, day - 1, 2),
        )
        val result = TaskBoard.carryOver(yesterday + board("hoy", forDay = day), day)
        assertEquals(listOf("hoy", "primera", "segunda", "tercera"), titles(result.tasks))
    }

    @Test fun carryOverStopsWhenTodayIsFull() {
        val all = board(*Array(8) { "h$it" }, forDay = day) + board("a", "b", "c", "d", forDay = day - 1)
        val result = TaskBoard.carryOver(all, day)
        assertEquals(2, result.moved); assertEquals(2, result.left)
        assertEquals(10, TaskBoard.forDay(result.tasks, day).size)
        assertEquals(0, TaskBoard.carryOver(result.tasks, day).moved)
    }

    @Test fun carryOverIgnoresAncientAndFutureTasks() {
        val all = board("vieja", forDay = day - 20) + board("mañana", forDay = day + 1)
        assertEquals(0, TaskBoard.carryOver(all, day).moved)
    }

    @Test fun pruneForgetsOldDays() {
        val all = board("vieja", forDay = day - 30) + board("reciente", forDay = day - 2)
        assertEquals(listOf("reciente"), TaskBoard.prune(all, day).map { it.title })
    }

    @Test fun perfectDayNeedsThreeOrMoreAllDone() {
        var all = board("A", "B")
        all = TaskBoard.setDone(TaskBoard.setDone(all, "id-$day-0", true), "id-$day-1", true)
        assertFalse(TaskBoard.isPerfectDay(TaskBoard.forDay(all, day)))
        all = (TaskBoard.add(all, day, "C", "c") as TaskBoard.AddResult.Added).tasks
        assertFalse(TaskBoard.isPerfectDay(TaskBoard.forDay(all, day)))
        assertTrue(TaskBoard.isPerfectDay(TaskBoard.forDay(TaskBoard.setDone(all, "c", true), day)))
    }
}

class TripPolicyTest {
    @Test fun aTripOfNDaysIncludesToday() {
        assertEquals(1_000L, TripPolicy.start(1_000, 1, null).untilEpochDay)
        assertEquals(1_006L, TripPolicy.start(1_000, 7, null).untilEpochDay)
        assertEquals(1_029L, TripPolicy.start(1_000, 500, null).untilEpochDay) // tope de 30 días
        assertEquals(1_000L, TripPolicy.start(1_000, 0, null).untilEpochDay)
    }

    @Test fun theTripStartsOnTheDayOfTheNextAlarm() {
        val zone = java.time.ZoneId.of("Europe/Madrid")
        fun at(d: Int, h: Int) = java.time.ZonedDateTime.of(java.time.LocalDateTime.of(2026, 3, d, h, 0), zone).toInstant().toEpochMilli()
        val today = java.time.LocalDate.of(2026, 3, 2).toEpochDay()
        assertEquals(today, TripPolicy.startDay(today, emptyList(), zone))                       // sin alarmas: hoy
        assertEquals(today, TripPolicy.startDay(today, listOf(at(2, 18)), zone))                 // todavía suena hoy
        assertEquals(today + 1, TripPolicy.startDay(today, listOf(at(3, 6)), zone))              // preparado por la noche: mañana
        assertEquals(today + 1, TripPolicy.startDay(today, listOf(at(9, 6), at(3, 6)), zone))    // la más próxima
        assertEquals(today, TripPolicy.startDay(today, listOf(at(1, 6)), zone))                  // vencida: hoy
        // Un viaje de 1 día preparado por la noche cubre justo mañana.
        val trip = TripPolicy.start(today + 1, 1, null)
        assertTrue(TripPolicy.isActive(trip, today + 1)); assertEquals(today + 1, trip.untilEpochDay)
    }

    @Test fun activeUntilTheLastDayInclusive() {
        val trip = Trip(1_005, null)
        assertTrue(TripPolicy.isActive(trip, 1_005)); assertFalse(TripPolicy.isActive(trip, 1_006)); assertFalse(TripPolicy.isActive(null, 1_000))
        assertEquals(1_005L, TripPolicy.pauseUntilDay(trip, 1_003))
        assertNull(TripPolicy.pauseUntilDay(trip, 1_006))
        assertNull(TripPolicy.pauseUntilDay(Trip(1_005, "a"), 1_003)) // un ancla alternativo no pausa
        assertEquals(3, TripPolicy.remainingDays(trip, 1_003))
    }

    @Test fun travelAnchorReplacesTheAlarmsOwnWhileActive() {
        val trip = Trip(1_005, "travel")
        val exists = { id: String -> id == "travel" || id == "home" }
        assertEquals("travel", TripPolicy.effectiveAnchorId("home", trip, 1_003, exists))
        assertEquals("home", TripPolicy.effectiveAnchorId("home", trip, 1_006, exists)) // terminó
        assertEquals("home", TripPolicy.effectiveAnchorId("home", Trip(1_005, "gone"), 1_003, exists)) // se borró: nunca sin ancla
        assertEquals("home", TripPolicy.effectiveAnchorId("home", Trip(1_005, null), 1_003, exists)) // pausa
        assertEquals("home", TripPolicy.effectiveAnchorId("home", null, 1_003, exists))
        assertNull(TripPolicy.effectiveAnchorId(null, null, 1, exists))
    }

    @Test fun pausedRangesAreExcusedAndEarlyEndGivesDaysBack() {
        val ranges = listOf(DayRange(10, 12))
        assertEquals(setOf(10L, 11L, 12L), TripPolicy.excusedDays(ranges))
        assertEquals(listOf(DayRange(10, 10)), TripPolicy.truncatePausedRanges(ranges, 11))
        assertEquals(emptyList<DayRange>(), TripPolicy.truncatePausedRanges(ranges, 10))
        assertEquals(ranges, TripPolicy.truncatePausedRanges(ranges, 20))
        assertEquals(12, TripPolicy.addPausedRange((1..12).map { DayRange(it.toLong(), it.toLong()) }, 99, 99).size)
    }
}

class PausePlannerTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(LocalDateTime.of(2026, 3, d, h, m), zone).toInstant().toEpochMilli()
    private fun day(d: Int) = LocalDate.of(2026, 3, d).toEpochDay()
    private fun alarm(days: Set<Weekday> = emptySet(), next: Long? = null) = AlarmEntity("a", 6, 30, days, true, "q", next)
    private val everyDay = Weekday.entries.toSet()

    @Test fun aRepeatingAlarmJumpsToTheFirstDayAfterThePause() {
        val result = AlarmPlanner.enable(alarm(everyDay), at(2, 22, 0), zone, pauseUntilDay = day(5))
        assertEquals(at(6, 6, 30), result.nextFireAt)
        assertTrue(result.enabled)
    }

    @Test fun aRepeatingAlarmAlreadyBeyondThePauseIsUntouched() {
        val result = AlarmPlanner.enable(alarm(everyDay), at(2, 22, 0), zone, pauseUntilDay = day(2))
        assertEquals(at(3, 6, 30), result.nextFireAt)
    }

    @Test fun aOneShotInsideThePauseIsSkippedNotPostponed() {
        val result = AlarmPlanner.enable(alarm(), at(2, 22, 0), zone, pauseUntilDay = day(5))
        assertFalse(result.enabled); assertNull(result.nextFireAt)
    }

    @Test fun aOneShotAfterThePauseIsKept() {
        val result = AlarmPlanner.enable(alarm(), at(2, 22, 0), zone, pauseUntilDay = day(2))
        assertEquals(at(3, 6, 30), result.nextFireAt)
    }

    @Test fun weekdayAlarmDuringAPauseLandsOnTheNextSelectedWeekday() {
        // lun 2 de marzo 2026; pausa hasta el miércoles 4; alarma solo lunes y viernes -> viernes 6
        val result = AlarmPlanner.enable(alarm(setOf(Weekday.MONDAY, Weekday.FRIDAY)), at(2, 22, 0), zone, pauseUntilDay = day(4))
        assertEquals(at(6, 6, 30), result.nextFireAt)
    }

    @Test fun restoreUnderPauseRecomputesEverything() {
        val repeating = AlarmPlanner.restore(alarm(everyDay, next = at(3, 6, 30)), at(2, 22, 0), zone, pauseUntilDay = day(5))
        assertEquals(at(6, 6, 30), repeating.nextFireAt)
        val oneShot = AlarmPlanner.restore(alarm(next = at(3, 6, 30)), at(2, 22, 0), zone, pauseUntilDay = day(5))
        assertFalse(oneShot.enabled)
        val after = AlarmPlanner.restore(alarm(next = at(9, 6, 30)), at(2, 22, 0), zone, pauseUntilDay = day(5))
        assertEquals(at(9, 6, 30), after.nextFireAt)
    }

    @Test fun aFiringThatArrivesDuringAPauseDoesNotRing() {
        val a = alarm(next = at(3, 6, 30))
        assertFalse(AlarmPlanner.shouldRing(a, at(3, 6, 30), pauseUntilDay = day(5), zone = zone))
        assertTrue(AlarmPlanner.shouldRing(a, at(3, 6, 30), pauseUntilDay = null, zone = zone))
        assertTrue(AlarmPlanner.shouldRing(a, at(3, 6, 30), pauseUntilDay = day(2), zone = zone))
    }
}

class GameEngineTest {
    private val today = LocalDate.of(2026, 3, 4)
    private val day = today.toEpochDay()

    private fun stateWith(vararg titles: String, base: ZenState = ZenState()): ZenState {
        var tasks = base.tasks
        titles.forEachIndexed { i, t -> tasks = (TaskBoard.add(tasks, day, t, "t$i") as TaskBoard.AddResult.Added).tasks }
        return base.copy(tasks = tasks)
    }

    private fun complete(state: ZenState, id: String) = GameEngine.setTaskDone(state, id, true, today)

    @Test fun xpDependsOnThePriorityOfTheSlot() {
        val s = stateWith("alta", "media", "baja1", "baja2", "baja3")
        assertEquals(XpRules.TASK_HIGH, complete(s, "t0").events.xpGained)
        assertEquals(XpRules.TASK_MEDIUM, complete(s, "t1").events.xpGained)
        assertEquals(XpRules.TASK_LOW, complete(s, "t4").events.xpGained)
    }

    @Test fun unmarkingAndRemarkingNeverPaysTwice() {
        var r = complete(stateWith("a"), "t0")
        val xp = r.state.progress.xp
        r = GameEngine.setTaskDone(r.state, "t0", false, today)
        assertEquals(xp, r.state.progress.xp)
        r = complete(r.state, "t0")
        assertEquals(0, r.events.xpGained)
        assertEquals(xp, r.state.progress.xp)
        assertEquals(1, r.state.progress.tasksCompleted)
    }

    @Test fun draggingAfterCompletingDoesNotChangeTheXpAlreadyEarned() {
        var s = stateWith("a", "b", "c", "d", "e")
        s = complete(s, "t4").state          // baja: 5 XP
        s = s.copy(tasks = TaskBoard.move(s.tasks, day, 4, 0))
        assertEquals(XpRules.TASK_LOW, s.progress.xp)
    }

    @Test fun onlyTodaysTasksCanBeCompleted() {
        val tomorrow = (TaskBoard.add(emptyList(), day + 1, "mañana", "m") as TaskBoard.AddResult.Added).tasks
        val r = GameEngine.setTaskDone(ZenState(tasks = tomorrow), "m", true, today)
        assertFalse(r.state.tasks.single().done); assertTrue(r.events.isEmpty)
        assertTrue(GameEngine.setTaskDone(ZenState(), "nope", true, today).events.isEmpty)
    }

    @Test fun dailyTaskXpIsCapped() {
        var s = stateWith(*Array(10) { "t$it" })
        // Prioritaria 30 + 3 medias 45 + 6 bajas 30 = 105 > 100: el tope recorta lo último.
        for (i in 0 until 10) s = complete(s, "t$i").state
        val taskXp = s.progress.taskXpToday
        assertEquals(XpRules.DAILY_TASK_XP_CAP, taskXp)
        // Borrar una hecha y volver a crearla otra vez no da más.
        val (after, _) = TaskBoard.delete(s.tasks, "t9")
        s = s.copy(tasks = (TaskBoard.add(after, day, "otra", "z") as TaskBoard.AddResult.Added).tasks)
        s = complete(s, "z").state
        assertEquals(XpRules.DAILY_TASK_XP_CAP, s.progress.taskXpToday)
    }

    @Test fun theCapResetsTheNextDay() {
        var s = stateWith("a").let { complete(it, "t0").state }
        assertEquals(30, s.progress.taskXpToday)
        val tomorrow = today.plusDays(1)
        s = s.copy(tasks = (TaskBoard.add(s.tasks, tomorrow.toEpochDay(), "b", "b") as TaskBoard.AddResult.Added).tasks)
        s = GameEngine.setTaskDone(s, "b", true, tomorrow).state
        assertEquals(30, s.progress.taskXpToday)
        assertEquals(60, s.progress.xp)
    }

    @Test fun completingThePriorityTaskCountsAsMitAndStartsAStreak() {
        val r = complete(stateWith("alta", "otra"), "t0")
        assertTrue(r.events.mitDone)
        assertEquals(1, r.state.progress.mitsCompleted)
        assertEquals(1, r.state.progress.mitStreak.currentStreak)
        assertTrue(Achievement.FIRST_MIT in r.events.unlocked)
        assertFalse(complete(stateWith("alta", "otra"), "t1").events.mitDone)
    }

    @Test fun mitStreakGrowsDayAfterDay() {
        var s = ZenState()
        for (i in 0 until 3) {
            val d = today.plusDays(i.toLong())
            s = s.copy(tasks = (TaskBoard.add(s.tasks, d.toEpochDay(), "mit", "m$i") as TaskBoard.AddResult.Added).tasks)
            s = GameEngine.setTaskDone(s, "m$i", true, d).state
        }
        assertEquals(3, s.progress.mitStreak.currentStreak)
        assertTrue(Achievement.MIT_STREAK_3.id in s.progress.achievements)
    }

    @Test fun wakeAndMitTheSameDayGiveTheCompleteDayBonusInEitherOrder() {
        // despertar primero
        var s = GameEngine.recordWake(stateWith("alta"), today, 50).state
        val afterWake = s.progress.xp
        assertEquals(XpRules.WAKE, afterWake)
        val r = complete(s, "t0")
        assertTrue(r.events.completeDay)
        assertEquals(afterWake + XpRules.TASK_HIGH + XpRules.COMPLETE_DAY_BONUS, r.state.progress.xp)
        // tarea primero
        s = complete(stateWith("alta"), "t0").state
        val w = GameEngine.recordWake(s, today, 50)
        assertTrue(w.events.completeDay)
        assertEquals(XpRules.TASK_HIGH + XpRules.WAKE + XpRules.COMPLETE_DAY_BONUS, w.state.progress.xp)
        // y solo una vez al día
        assertEquals(0, GameEngine.recordWake(w.state, today, 30).events.xpGained)
    }

    @Test fun aPerfectDayPaysOnce() {
        var s = stateWith("a", "b", "c")
        s = complete(s, "t0").state; s = complete(s, "t1").state
        val r = complete(s, "t2")
        assertTrue(r.events.perfectDay)
        assertEquals(1, r.state.progress.perfectDays)
        assertTrue(Achievement.PERFECT_DAY in r.events.unlocked)
        val again = GameEngine.setTaskDone(GameEngine.setTaskDone(r.state, "t2", false, today).state, "t2", true, today)
        assertFalse(again.events.perfectDay)
        assertEquals(1, again.state.progress.perfectDays)
    }

    @Test fun tenOfTenUnlocksFullHouse() {
        var s = stateWith(*Array(10) { "t$it" })
        for (i in 0 until 10) s = complete(s, "t$i").state
        assertTrue(Achievement.FULL_HOUSE.id in s.progress.achievements)
        assertEquals(1, s.progress.fullDays)
    }

    @Test fun wakingGivesXpOncePerDayAndUnlocksAchievements() {
        val r = GameEngine.recordWake(ZenState(), today, 45)
        assertEquals(XpRules.WAKE, r.events.xpGained)
        assertTrue(Achievement.FIRST_WAKE in r.events.unlocked)
        assertTrue(Achievement.QUICK_RISER in r.events.unlocked)
        assertEquals(0, GameEngine.recordWake(r.state, today, 20).events.xpGained)
        assertFalse(Achievement.QUICK_RISER in GameEngine.recordWake(ZenState(), today, 300).events.unlocked)
    }

    @Test fun achievementsUnlockOnlyOnce() {
        var s = ZenState()
        var total = 0
        for (i in 0 until 3) {
            val r = GameEngine.recordWake(s, today.plusDays(i.toLong()), 200)
            s = r.state; total += r.events.unlocked.count { it == Achievement.FIRST_WAKE }
        }
        assertEquals(1, total)
        assertTrue(Achievement.STREAK_3.id in s.progress.achievements)
        assertEquals(day, s.progress.achievements[Achievement.FIRST_WAKE.id])
    }

    @Test fun levelsFollowTheFormula() {
        assertEquals(0, Levels.xpToReach(1)); assertEquals(100, Levels.xpToReach(2)); assertEquals(300, Levels.xpToReach(3)); assertEquals(1000, Levels.xpToReach(5))
        assertEquals(1, Levels.levelFor(0)); assertEquals(1, Levels.levelFor(99)); assertEquals(2, Levels.levelFor(100)); assertEquals(5, Levels.levelFor(1000))
        val p = Levels.progress(150)
        assertEquals(2, p.level); assertEquals(50, p.xpIntoLevel); assertEquals(200, p.xpForNext); assertEquals(0.25f, p.fraction, 1e-6f)
        assertEquals("Despertando", Levels.title(1)); assertEquals("Maestro del alba", Levels.title(99))
        assertEquals(1, Levels.levelFor(-50))
    }

    @Test fun aHugeOrCorruptXpNeverHangsOrOverflows() {
        assertEquals(Levels.MAX_LEVEL, Levels.levelFor(Int.MAX_VALUE))
        assertEquals(1, Levels.levelFor(Int.MIN_VALUE))
        val p = Levels.progress(Int.MAX_VALUE)
        assertEquals(Levels.MAX_LEVEL, p.level); assertTrue(p.fraction in 0f..1f); assertTrue(p.xpForNext > 0)
        assertEquals(Levels.MAX_XP, Levels.addXp(Levels.MAX_XP - 1, 1_000_000))
        assertEquals(0, Levels.addXp(5, -100))
        val capped = GameEngine.recordWake(ZenState(progress = Progress(xp = Levels.MAX_XP)), today, 100).state.progress.xp
        assertEquals(Levels.MAX_XP, capped)
    }

    @Test fun levelUpIsReported() {
        val base = ZenState(progress = Progress(xp = 95))
        val r = GameEngine.recordWake(base, today, 100)
        assertTrue(r.events.leveledUp); assertEquals(1, r.events.levelBefore); assertEquals(2, r.events.levelAfter)
    }

    @Test fun startingAPauseExcusesItsDaysAndKeepsTheStreakAlive() {
        // Racha de 3 días hasta ayer; pausa de 5 días desde hoy; al volver el día 6 la racha continúa.
        var s = ZenState()
        for (i in 3 downTo 1) s = GameEngine.recordWake(s, today.minusDays(i.toLong()), 100).state
        assertEquals(3, s.stats.currentStreak)
        s = GameEngine.startTrip(s, TripPolicy.start(day, 5, null), today).state
        assertEquals(1, s.progress.tripsStarted)
        assertTrue(Achievement.TRAVELER.id in s.progress.achievements)
        val back = today.plusDays(5)
        val r = GameEngine.recordWake(s, back, 100)
        assertEquals(4, r.state.stats.currentStreak)
    }

    @Test fun withoutThePauseTheSameGapBreaksTheStreak() {
        var s = ZenState()
        for (i in 3 downTo 1) s = GameEngine.recordWake(s, today.minusDays(i.toLong()), 100).state
        assertEquals(1, GameEngine.recordWake(s, today.plusDays(5), 100).state.stats.currentStreak)
    }

    @Test fun anAnchorTripDoesNotExcuseAnyDay() {
        var s = ZenState(anchors = listOf(QrAnchor("hotel", "Hotel", "s", "000000")))
        for (i in 3 downTo 1) s = GameEngine.recordWake(s, today.minusDays(i.toLong()), 100).state
        s = GameEngine.startTrip(s, TripPolicy.start(day, 5, "hotel"), today).state
        assertTrue(s.progress.pausedRanges.isEmpty())
        assertEquals(1, GameEngine.recordWake(s, today.plusDays(5), 100).state.stats.currentStreak)
    }

    @Test fun cancellingAPauseEarlyGivesTheUnusedDaysBack() {
        var s = GameEngine.startTrip(ZenState(), TripPolicy.start(day, 7, null), today).state
        s = GameEngine.cancelTrip(s, today.plusDays(2))
        assertNull(s.trip)
        assertEquals(setOf(day, day + 1), TripPolicy.excusedDays(s.progress.pausedRanges))
        assertEquals(s, GameEngine.cancelTrip(s, today))
    }

    @Test fun startingATripReplacesThePreviousOne() {
        var s = GameEngine.startTrip(ZenState(), TripPolicy.start(day, 7, null), today).state
        s = GameEngine.startTrip(s, TripPolicy.start(day, 2, "x"), today.plusDays(1)).state
        assertEquals("x", s.trip?.anchorId)
        assertEquals(2, s.progress.tripsStarted)
    }

    @Test fun anAnchorInUseByATripCannotBeDeleted() {
        val s = ZenState(anchors = listOf(QrAnchor("a", "x", "s", "000000")), trip = Trip(10, "a"))
        assertTrue(s.isAnchorInUse("a"))
        assertFalse(ZenState(anchors = s.anchors).isAnchorInUse("a"))
    }

    @Test fun displayedStreakIgnoresExcusedDays() {
        val wake = WakeStats(currentStreak = 4, bestStreak = 4, totalWakeups = 4, lastWakeDay = day - 6)
        assertEquals(0, StreakPolicy.displayStreak(wake, today, emptySet()))
        assertEquals(4, StreakPolicy.displayStreak(wake, today, emptySet(), (day - 5..day - 1).toSet()))
    }
}
