import Foundation
import SwiftData

@Model
final class TaskItem {
    var id: UUID
    var text: String
    var isDone: Bool
    var isMIT: Bool
    /// Bucket de día local, ej. "2026-08-24". Se calcula con el calendario/zona
    /// horaria del dispositivo para que un cambio de zona horaria (viaje) no
    /// reclasifique tareas de ayer como de hoy.
    var dayKey: String
    var createdAt: Date
    var completedAt: Date?

    init(
        id: UUID = UUID(),
        text: String,
        isMIT: Bool = false,
        dayKey: String = TaskItem.todayKey()
    ) {
        self.id = id
        self.text = text
        self.isDone = false
        self.isMIT = isMIT
        self.dayKey = dayKey
        self.createdAt = Date()
        self.completedAt = nil
    }

    static func todayKey(for date: Date = Date(), calendar: Calendar = .current) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.timeZone = calendar.timeZone
        return formatter.string(from: date)
    }

    func markDone(at date: Date = Date()) {
        isDone = true
        completedAt = date
    }
}
