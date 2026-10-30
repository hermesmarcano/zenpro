import Testing
import Foundation
@testable import ZEN

struct ArmedRecoveryPolicyTests {
    private let fire = Date(timeIntervalSince1970: 1_000_000)
    private var record: ArmedRecord { ArmedRecord(alarmID: UUID(), fireDate: fire) }

    @Test func noRecordMeansNothingToDo() {
        #expect(ArmedRecoveryPolicy.decide(record: nil, now: fire) == .nothing)
    }

    @Test func beforeTheFireTimeItReArms() {
        #expect(ArmedRecoveryPolicy.decide(record: record, now: fire.addingTimeInterval(-3_600)) == .rearm)
        #expect(ArmedRecoveryPolicy.decide(record: record, now: fire.addingTimeInterval(-1)) == .rearm)
    }

    @Test func rightAfterTheFireTimeItAsksForProof() {
        #expect(ArmedRecoveryPolicy.decide(record: record, now: fire) == .presentRinging)
        #expect(ArmedRecoveryPolicy.decide(record: record, now: fire.addingTimeInterval(10 * 60)) == .presentRinging)
    }

    @Test func theUnresolvedWindowIsInclusiveAtItsEdge() {
        let edge = fire.addingTimeInterval(ArmedRecoveryPolicy.unresolvedWindow)
        #expect(ArmedRecoveryPolicy.decide(record: record, now: edge) == .presentRinging)
        #expect(ArmedRecoveryPolicy.decide(record: record, now: edge.addingTimeInterval(1)) == .repairSchedule)
    }

    @Test func muchLaterOnlyTheScheduleIsRepaired() {
        #expect(ArmedRecoveryPolicy.decide(record: record, now: fire.addingTimeInterval(8 * 3_600)) == .repairSchedule)
    }

    @Test func recordSurvivesAJSONRoundTrip() throws {
        let original = record
        let data = try JSONEncoder().encode(original)
        #expect(try JSONDecoder().decode(ArmedRecord.self, from: data) == original)
    }
}

struct BackupCodePolicyTests {
    @Test func isLockedAtTheStartAndFullyUnlockedAfterTheDelay() {
        #expect(BackupCodePolicy.remaining(since: 100, now: 100) == BackupCodePolicy.delay)
        #expect(!BackupCodePolicy.isAvailable(since: 100, now: 100))
        #expect(!BackupCodePolicy.isAvailable(since: 100, now: 100 + BackupCodePolicy.delay - 0.1))
        #expect(BackupCodePolicy.isAvailable(since: 100, now: 100 + BackupCodePolicy.delay))
        #expect(BackupCodePolicy.remaining(since: 100, now: 100 + 10_000) == 0)
    }

    @Test func remainingCountsDownLinearly() {
        #expect(BackupCodePolicy.remaining(since: 0, now: 30) == BackupCodePolicy.delay - 30)
    }

    @Test func aClockGoingBackwardsNeverUnlocksEarly() {
        #expect(BackupCodePolicy.remaining(since: 500, now: 100) == BackupCodePolicy.delay)
        #expect(!BackupCodePolicy.isAvailable(since: 500, now: 100))
    }

    @Test func sanitizeKeepsOnlyAsciiDigitsUpToSix() {
        #expect(BackupCodePolicy.sanitize("12a3-45 6789") == "123456")
        #expect(BackupCodePolicy.sanitize("abc") == "")
        #expect(BackupCodePolicy.sanitize("") == "")
        #expect(BackupCodePolicy.sanitize("١٢٣") == "") // dígitos arábigo-índicos: no son ASCII
        #expect(BackupCodePolicy.sanitize("007") == "007")
    }

    @Test func countdownTextFormatsMinutesAndSeconds() {
        #expect(BackupCodePolicy.countdownText(120) == "2:00")
        #expect(BackupCodePolicy.countdownText(65) == "1:05")
        #expect(BackupCodePolicy.countdownText(0.2) == "0:01")
        #expect(BackupCodePolicy.countdownText(0) == "0:00")
    }
}

struct WeekdayLabelsTests {
    @Test func weekStartsOnMondayAndCoversEveryDayOnce() {
        #expect(Weekday.mondayFirst.first == .monday)
        #expect(Weekday.mondayFirst.last == .sunday)
        #expect(Set(Weekday.mondayFirst) == Set(Weekday.allCases))
        #expect(Weekday.mondayFirst.count == 7)
    }

    @Test func wednesdayIsXAsIsCustomaryInSpanish() {
        #expect(Weekday.wednesday.shortLabel == "X")
        #expect(Weekday.wednesday.fullName == "miércoles")
    }

    @Test func summaryHandlesOnceAndEveryDayAndOrdering() {
        #expect(Weekday.summary(of: []) == "Una vez")
        #expect(Weekday.summary(of: Set(Weekday.allCases)) == "Todos los días")
        #expect(Weekday.summary(of: [.sunday, .monday, .friday]) == "L V D")
    }
}
