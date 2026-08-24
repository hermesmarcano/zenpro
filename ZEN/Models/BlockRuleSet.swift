import Foundation
import SwiftData

struct ScheduleWindow: Codable, Identifiable, Hashable, Sendable {
    var id: UUID = UUID()
    var startHour: Int
    var startMinute: Int
    var endHour: Int
    var endMinute: Int
    var days: Set<Weekday>
    var isUnlockWindow: Bool
}

@Model
final class BlockRuleSet {
    var id: UUID
    /// `FamilyActivitySelection` codificada — la app nunca decodifica esto para leer
    /// nombres/iconos reales (Apple lo mantiene opaco por privacidad). Se pasa tal
    /// cual a `ManagedSettingsStore`.
    var selectionData: Data?
    var scheduleWindowsData: Data
    var isEnabled: Bool

    init(
        id: UUID = UUID(),
        selectionData: Data? = nil,
        scheduleWindows: [ScheduleWindow] = [],
        isEnabled: Bool = true
    ) {
        self.id = id
        self.selectionData = selectionData
        self.scheduleWindowsData = (try? JSONEncoder().encode(scheduleWindows)) ?? Data()
        self.isEnabled = isEnabled
    }

    var scheduleWindows: [ScheduleWindow] {
        get { (try? JSONDecoder().decode([ScheduleWindow].self, from: scheduleWindowsData)) ?? [] }
        set { scheduleWindowsData = (try? JSONEncoder().encode(newValue)) ?? Data() }
    }
}
