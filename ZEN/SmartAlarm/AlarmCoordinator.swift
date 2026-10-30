import Combine
import Foundation

/// Punto único que decide qué alarma debe mostrar la pantalla de apagado y cómo se
/// silencia cuando el usuario demuestra presencia (QR o foto):
///  - alarma inteligente armada y sonando desde ZEN -> `SmartAlarmSession`;
///  - alarma del sistema (AlarmKit) sonando, sin armar -> `AlarmScheduler.stop`.
@MainActor
final class AlarmCoordinator: ObservableObject {
    static let shared = AlarmCoordinator()

    let session = SmartAlarmSession()
    @Published private(set) var systemRingingAlarmID: UUID?

    private var sessionObservation: AnyCancellable?

    init() {
        // Reenvía los cambios de la sesión para que las vistas que observan el
        // coordinador se actualicen también.
        sessionObservation = session.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
    }

    var activeRingingAlarmID: UUID? {
        session.ringingAlarmID ?? systemRingingAlarmID
    }

    /// La alarma del sistema está sonando y el usuario tocó "Abrir ZEN".
    func systemAlarmIsRinging(id: UUID?) {
        systemRingingAlarmID = id
    }

    func dismissalCompleted(alarm: AlarmConfig) async {
        if session.ringingAlarmID == alarm.id {
            await session.finishRinging()
        } else {
            try? await AlarmScheduler.stop(alarm)
        }
        systemRingingAlarmID = nil
    }
}
