import Testing
import SwiftData
@testable import ZEN

@MainActor
struct AlarmStoreTests {
    private func makeContext() throws -> ModelContext {
        let container = try ModelContainer(
            for: AlarmConfig.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        return ModelContext(container)
    }

    @Test func createsAlarmWithGivenFields() throws {
        let context = try makeContext()
        let store = AlarmStore(context: context)
        let anchorID = UUID()
        let alarm = store.create(hour: 6, minute: 30, repeatDays: [.monday], anchorID: anchorID)
        #expect(alarm.hour == 6)
        #expect(alarm.minute == 30)
        #expect(alarm.repeatDays == [.monday])
        #expect(alarm.anchorID == anchorID)
    }

    @Test func allAlarmsSortedByTime() throws {
        let context = try makeContext()
        let store = AlarmStore(context: context)
        _ = store.create(hour: 8, minute: 0, repeatDays: [], anchorID: nil)
        _ = store.create(hour: 6, minute: 30, repeatDays: [], anchorID: nil)
        let alarms = store.allAlarms()
        #expect(alarms.map(\.hour) == [6, 8])
    }

    @Test func deleteRemovesAlarm() throws {
        let context = try makeContext()
        let store = AlarmStore(context: context)
        let alarm = store.create(hour: 6, minute: 30, repeatDays: [], anchorID: nil)
        store.delete(alarm)
        #expect(store.allAlarms().isEmpty)
    }
}
