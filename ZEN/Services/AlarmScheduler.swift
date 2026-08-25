import AlarmKit
import Foundation
import SwiftUI

/// Encapsula AlarmKit por completo — es la única pieza del código que interactúa
/// directamente con el framework. Si la firma real difiere de lo escrito aquí de
/// memoria, el error de compilación de CI señala exactamente este archivo; el
/// resto de la app (AlarmEditView, AlarmRingingView) solo conoce `schedule`/`stop`.
///
/// [VERIFICAR] AlarmKit es nuevo (iOS 26) — estas firmas no se pudieron comprobar
/// contra Xcode local. Iterar contra el error real del compilador en CI.
@MainActor
enum AlarmScheduler {
    struct Metadata: AlarmMetadata {
        let alarmConfigID: UUID
    }

    static func schedule(_ config: AlarmConfig) async throws {
        let attributes = AlarmAttributes<Metadata>(
            presentation: presentation(),
            metadata: Metadata(alarmConfigID: config.id),
            tintColor: Color(red: 0.89, green: 0.64, blue: 0.35)
        )

        let configuration = AlarmManager.AlarmConfiguration(
            schedule: schedule(for: config),
            attributes: attributes,
            stopIntent: nil,
            secondaryIntent: nil,
            sound: .default
        )

        try await AlarmManager.shared.schedule(id: config.id, configuration: configuration)
    }

    static func stop(_ config: AlarmConfig) async throws {
        try await AlarmManager.shared.stop(id: config.id)
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

    private static func schedule(for config: AlarmConfig) -> Alarm.Schedule {
        let time = Alarm.Schedule.Relative.Time(hour: config.hour, minute: config.minute)
        let recurrence: Alarm.Schedule.Relative.Recurrence = config.repeatDays.isEmpty
            ? .never
            : .weekly(config.repeatDays.map(localeWeekday(from:)))
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
