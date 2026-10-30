import AppIntents
import Foundation

/// Acción del botón "Abrir ZEN" de la alarma del sistema: abre la app y le avisa de
/// qué alarma está sonando para que muestre la pantalla donde se apaga con el QR o la
/// foto. Sin esto el botón no tendría forma de llevar al usuario hasta ZEN.
struct OpenZENIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Abrir ZEN"
    static let openAppWhenRun = true

    @Parameter(title: "Alarma")
    var alarmID: String

    init() {
        alarmID = ""
    }

    init(alarmID: String) {
        self.alarmID = alarmID
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        AlarmCoordinator.shared.systemAlarmIsRinging(id: UUID(uuidString: alarmID))
        return .result()
    }
}
