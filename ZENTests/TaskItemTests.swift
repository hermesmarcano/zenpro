import Testing
import Foundation
@testable import ZEN

struct TaskItemTests {
    @Test func newTaskIsNotDone() {
        let task = TaskItem(text: "Llamar al banco")
        #expect(!task.isDone)
        #expect(task.completedAt == nil)
    }

    @Test func markDoneSetsTimestamp() {
        let task = TaskItem(text: "Responder correos")
        task.markDone()
        #expect(task.isDone)
        #expect(task.completedAt != nil)
    }

    @Test func todayKeyIsStableFormat() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Caracas")!
        let components = DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: 2026,
            month: 8,
            day: 24,
            hour: 10
        )
        let date = try #require(components.date)
        #expect(TaskItem.todayKey(for: date, calendar: calendar) == "2026-08-24")
    }
}
