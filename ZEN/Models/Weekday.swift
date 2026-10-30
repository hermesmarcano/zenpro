import Foundation

/// Día de la semana con la misma numeración que `Calendar` (1 = domingo … 7 = sábado).
enum Weekday: Int, Codable, CaseIterable, Identifiable, Sendable {
    case sunday = 1, monday, tuesday, wednesday, thursday, friday, saturday
    var id: Int { rawValue }
}
