import Foundation

/// Día de la semana con la misma numeración que `Calendar` (1 = domingo … 7 = sábado).
enum Weekday: Int, Codable, CaseIterable, Identifiable, Sendable {
    case sunday = 1, monday, tuesday, wednesday, thursday, friday, saturday
    var id: Int { rawValue }
}

extension Weekday {
    /// Orden de la semana en español (empieza en lunes).
    static let mondayFirst: [Weekday] = [
        .monday, .tuesday, .wednesday, .thursday, .friday, .saturday, .sunday,
    ]

    /// Inicial de una letra ("X" para miércoles, como es costumbre en español).
    var shortLabel: String {
        switch self {
        case .monday: "L"
        case .tuesday: "M"
        case .wednesday: "X"
        case .thursday: "J"
        case .friday: "V"
        case .saturday: "S"
        case .sunday: "D"
        }
    }

    /// Nombre completo, para VoiceOver.
    var fullName: String {
        switch self {
        case .monday: "lunes"
        case .tuesday: "martes"
        case .wednesday: "miércoles"
        case .thursday: "jueves"
        case .friday: "viernes"
        case .saturday: "sábado"
        case .sunday: "domingo"
        }
    }

    /// "L M X" / "Una vez" para el resumen de una alarma.
    static func summary(of days: Set<Weekday>) -> String {
        let ordered = mondayFirst.filter { days.contains($0) }
        guard !ordered.isEmpty else { return "Una vez" }
        if ordered.count == 7 { return "Todos los días" }
        return ordered.map(\.shortLabel).joined(separator: " ")
    }
}
