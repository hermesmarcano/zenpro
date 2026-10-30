import Testing
import Foundation
@testable import ZEN

struct ScheduleMathTests {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    /// 2026-10-30 es viernes.
    private func at(_ day: Int, month: Int = 10, hour: Int, minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: month, day: day, hour: hour, minute: minute))
            ?? .distantPast
    }

    private func next(after date: Date, hour: Int = 6, minute: Int = 30, weekdays: Set<Weekday> = []) -> Date? {
        NextFireCalculator.nextFireDate(after: date, hour: hour, minute: minute, weekdays: weekdays, calendar: calendar)
    }

    // MARK: - NextFireCalculator

    @Test func firesTodayWhenTheTimeHasNotPassed() {
        let result = next(after: at(30, hour: 5))
        #expect(result == at(30, hour: 6, minute: 30))
    }

    @Test func firesTomorrowWhenTheTimeHasPassed() {
        let result = next(after: at(30, hour: 7))
        #expect(result == at(31, hour: 6, minute: 30))
    }

    @Test func isStrictlyAfterTheReferenceInstant() {
        let result = next(after: at(30, hour: 6, minute: 30))
        #expect(result == at(31, hour: 6, minute: 30))
    }

    @Test func respectsTheChosenWeekdays() {
        // Viernes 07:00 con la alarma solo los lunes => lunes 2 de noviembre.
        let result = next(after: at(30, hour: 7), weekdays: [.monday])
        #expect(result == at(2, month: 11, hour: 6, minute: 30))
    }

    @Test func sameWeekdayAfterTheTimeWaitsAWeek() {
        let result = next(after: at(30, hour: 7), weekdays: [.friday])
        #expect(result == at(6, month: 11, hour: 6, minute: 30))
    }

    @Test func picksTheEarliestOfSeveralWeekdays() {
        let result = next(after: at(30, hour: 7), weekdays: [.sunday, .wednesday])
        #expect(result == at(1, month: 11, hour: 6, minute: 30))
    }

    @Test func rejectsOutOfRangeTimes() {
        #expect(next(after: at(30, hour: 5), hour: 24) == nil)
        #expect(next(after: at(30, hour: 5), minute: 60) == nil)
        #expect(next(after: at(30, hour: 5), hour: -1) == nil)
    }

    // MARK: - ScheduleShift

    @Test func shiftWithinTheSameDayKeepsWeekdays() {
        let shifted = ScheduleShift.shift(hour: 6, minute: 30, weekdays: [.monday, .friday], byMinutes: 2)
        #expect(shifted == ShiftedSchedule(hour: 6, minute: 32, weekdays: [.monday, .friday]))
    }

    @Test func shiftCarriesMinutesIntoTheHour() {
        let shifted = ScheduleShift.shift(hour: 6, minute: 59, weekdays: [], byMinutes: 2)
        #expect(shifted == ShiftedSchedule(hour: 7, minute: 1, weekdays: []))
    }

    @Test func shiftPastMidnightMovesWeekdaysForwardAndWrapsSaturday() {
        let shifted = ScheduleShift.shift(hour: 23, minute: 59, weekdays: [.monday, .saturday], byMinutes: 2)
        #expect(shifted == ShiftedSchedule(hour: 0, minute: 1, weekdays: [.tuesday, .sunday]))
    }

    @Test func negativeShiftBeforeMidnightMovesWeekdaysBack() {
        let shifted = ScheduleShift.shift(hour: 0, minute: 1, weekdays: [.sunday, .monday], byMinutes: -2)
        #expect(shifted == ShiftedSchedule(hour: 23, minute: 59, weekdays: [.saturday, .sunday]))
    }

    @Test func zeroShiftIsTheIdentity() {
        let shifted = ScheduleShift.shift(hour: 13, minute: 5, weekdays: [.wednesday], byMinutes: 0)
        #expect(shifted == ShiftedSchedule(hour: 13, minute: 5, weekdays: [.wednesday]))
    }

    @Test func hourAndMinuteAlwaysStayInRange() {
        var allInRange = true
        for delta in stride(from: -3_000, through: 3_000, by: 37) {
            let shifted = ScheduleShift.shift(hour: 23, minute: 59, weekdays: [.saturday], byMinutes: delta)
            if !(0...23).contains(shifted.hour) || !(0...59).contains(shifted.minute) || shifted.weekdays.count != 1 {
                allInRange = false
            }
        }
        #expect(allInRange)
    }
}

struct AlarmToneSynthTests {
    @Test func loopHasTheExpectedLength() {
        #expect(AlarmToneSynth.samples(sampleRate: 44_100).count == 88_200)
        #expect(AlarmToneSynth.samples(sampleRate: 8_000).count == 16_000)
    }

    @Test func neverClips() {
        let peak = AlarmToneSynth.samples().map { abs($0) }.max() ?? 0
        #expect(peak <= 1.0)
        #expect(peak > 0.5)
    }

    @Test func loopsWithoutClicks() {
        let samples = AlarmToneSynth.samples()
        #expect(samples.first == 0)
        #expect(samples.last == 0)
    }

    @Test func endsWithSilenceAfterTheBeeps() {
        let samples = AlarmToneSynth.samples(sampleRate: 44_100)
        let tail = samples[Int(1.0 * 44_100)...]
        let tailPeak = tail.map { abs($0) }.max() ?? 0
        #expect(tailPeak == 0)
    }

    @Test func beepsAreAudible() {
        let samples = AlarmToneSynth.samples(sampleRate: 44_100)
        let firstBeep = samples[0..<Int(0.12 * 44_100)]
        let peak = firstBeep.map { abs($0) }.max() ?? 0
        #expect(peak > 0.7)
    }

    @Test func silenceIsAllZeros() {
        let silence = AlarmToneSynth.silence()
        #expect(silence.count == 88_200)
        let allZero = silence.allSatisfy { $0 == 0 }
        #expect(allZero)
    }
}
