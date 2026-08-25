import Foundation
import SwiftData

@MainActor
struct AlarmStore {
    let context: ModelContext

    func allAlarms() -> [AlarmConfig] {
        let descriptor = FetchDescriptor<AlarmConfig>(
            sortBy: [SortDescriptor(\.hour), SortDescriptor(\.minute)]
        )
        return (try? context.fetch(descriptor)) ?? []
    }

    func create(hour: Int, minute: Int, repeatDays: Set<Weekday>, anchorID: UUID?) -> AlarmConfig {
        let alarm = AlarmConfig(hour: hour, minute: minute, repeatDays: repeatDays, anchorID: anchorID)
        context.insert(alarm)
        return alarm
    }

    func delete(_ alarm: AlarmConfig) {
        context.delete(alarm)
    }
}
