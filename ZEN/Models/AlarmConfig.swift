import Foundation
import SwiftData

@Model
final class AlarmConfig {
    var id: UUID
    var hour: Int
    var minute: Int
    var repeatDaysRaw: [Int]
    var isEnabled: Bool
    var soundName: String
    var escalationEnabled: Bool
    var anchorID: UUID?
    var graceSeconds: Int

    var repeatDays: Set<Weekday> {
        get { Set(repeatDaysRaw.compactMap(Weekday.init)) }
        set { repeatDaysRaw = newValue.map(\.rawValue) }
    }

    init(
        id: UUID = UUID(),
        hour: Int,
        minute: Int,
        repeatDays: Set<Weekday> = [],
        isEnabled: Bool = true,
        soundName: String = "tension",
        escalationEnabled: Bool = true,
        anchorID: UUID? = nil,
        graceSeconds: Int = 120
    ) {
        precondition((0...23).contains(hour), "hour fuera de rango: \(hour)")
        precondition((0...59).contains(minute), "minute fuera de rango: \(minute)")
        self.id = id
        self.hour = hour
        self.minute = minute
        self.repeatDaysRaw = repeatDays.map(\.rawValue)
        self.isEnabled = isEnabled
        self.soundName = soundName
        self.escalationEnabled = escalationEnabled
        self.anchorID = anchorID
        self.graceSeconds = graceSeconds
    }
}
