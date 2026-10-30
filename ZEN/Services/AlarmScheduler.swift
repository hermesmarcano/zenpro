import AlarmKit
import Foundation
import SwiftUI

/// Encapsula AlarmKit por completo — es la única pieza del código que interactúa
/// directamente con el framework. Si la firma real difiere de lo escrito aquí de
/// memoria, el error de compilación de CI señala exactamente este archivo; el
/// resto de la app solo conoce `schedule` / `stop` / `cancel`.
///
/// [VERIFICAR] AlarmKit es nuevo (iOS 26) — estas firmas no se pudieron comprobar
/// contra Xcode local. Iterar contra el error real del compilador en CI.
@MainActor
enum AlarmScheduler {
    struct Metadata: AlarmMetadata {
        let alarmConfigID: UUID
    }

    /// Programa la alarma del sistema. Con `delayMinutes > 0` se desplaza ese margen
    /// (red de seguridad mientras la alarma inteligente está armada: si iOS cierra
    /// ZEN durante la noche, esta alarma suena igual, con ese retraso).
    static func schedule(_ config: AlarmConfig, delayMinutes: Int = 0) async throws {
        let attributes = AlarmAttributes<Metadata>(
            presentation: presentation(),
            metadata: Metadata(alarmConfigID: config.id),
            tintColor: Color(red: 0.89, green: 0.64, blue: 0.35)
        )

        let configuration = AlarmManager.AlarmConfiguration(
            schedule: schedule(for: config, delayMinutes: delayMinutes),
            attributes: attributes,
            stopIntent: nil,
            secondaryIntent: OpenZENIntent(alarmID: config.id.uuidString),
            sound: .default
        )

        try await AlarmManager.shared.schedule(id: config.id, configuration: configuration)
    }

    /// Silencia una alarma que está sonando. Si es repetitiva, el sistema la deja
    /// programada para su siguiente ocurrencia.
    static func stop(_ config: AlarmConfig) async throws {
        try await AlarmManager.shared.stop(id: config.id)
    }

    /// Elimina la alarma del sistema por completo (programada o sonando).
    static func cancel(_ config: AlarmConfig) async throws {
        try await AlarmManager.shared.cancel(id: config.id)
    }

    private static func presentation() -> AlarmPresentation {
        // AlarmKit exige un `stopButton` real y no-opcional: Apple no permite que
        // ninguna app construya una alarma que el sistema no pueda silenciar
        // directamente (la misma filosofía de plataforma que garantiza que el
        // usuario siempre puede desactivar Screen Time desde Ajustes). No se puede
        // ocultar ni deshabilitar — el botón es honesto sobre lo que hace ("Detener").
        // El flujo de fricción real de ZEN vive en el botón secundario, que abre la
        // app para escanear el QR (`secondaryButtonBehavior: .custom`).
        //
        // Rastrear cuándo se usó "Detener" sin pasar por el QR (para reflejarlo en
        // el dashboard, sin fingir que no pasó) requiere un `stopIntent` propio —
        // queda como mejora de v1.1, no bloquea el MVP.
        let alert = AlarmPresentation.Alert(
            title: "ZEN",
            stopButton: AlarmButton(
                text: "Detener",
                textColor: .white,
                systemImageName: "xmark"
            ),
            secondaryButton: AlarmButton(
                text: "Abrir ZEN",
                textColor: .black,
                systemImageName: "qrcode.viewfinder"
            ),
            secondaryButtonBehavior: .custom
        )
        return AlarmPresentation(alert: alert)
    }

    private static func schedule(for config: AlarmConfig, delayMinutes: Int) -> Alarm.Schedule {
        let shifted = ScheduleShift.shift(
            hour: config.hour,
            minute: config.minute,
            weekdays: config.repeatDays,
            byMinutes: delayMinutes
        )
        let time = Alarm.Schedule.Relative.Time(hour: shifted.hour, minute: shifted.minute)
        let recurrence: Alarm.Schedule.Relative.Recurrence = shifted.weekdays.isEmpty
            ? .never
            : .weekly(shifted.weekdays.map(localeWeekday(from:)))
        return .relative(Alarm.Schedule.Relative(time: time, repeats: recurrence))
    }

    private static func localeWeekday(from day: Weekday) -> Locale.Weekday {
        switch day {
        case .sunday: .sunday
        case .monday: .monday
        case .tuesday: .tuesday
        case .wednesday: .wednesday
        case .thursday: .thursday
        case .friday: .friday
        case .saturday: .saturday
        }
    }
}
