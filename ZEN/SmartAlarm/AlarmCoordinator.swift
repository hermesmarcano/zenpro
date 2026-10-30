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
    private var hasRecoveredFromLaunch = false

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
            // Si la alarma había quedado armada y iOS cerró ZEN, su programación sigue
            // desplazada `hora + 2 min`. Sin restaurarla, una alarma repetitiva seguiría
            // sonando 2 minutos tarde todos los días.
            if !alarm.repeatDays.isEmpty {
                try? await AlarmScheduler.cancel(alarm)
                try? await AlarmScheduler.schedule(alarm)
            }
            ArmedRecordStore.clear()
        }
        systemRingingAlarmID = nil
    }

    /// Al abrir la app: si quedó un registro de "armada" sin cerrar, iOS (o el usuario)
    /// terminó ZEN con la alarma armada. Se actúa según `ArmedRecoveryPolicy`.
    func recoverFromLaunch(alarms: [AlarmConfig], now: Date = Date()) async {
        guard !hasRecoveredFromLaunch, session.state == .idle else { return }
        hasRecoveredFromLaunch = true

        let record = ArmedRecordStore.load()
        guard let record, let alarm = alarms.first(where: { $0.id == record.alarmID }) else {
            ArmedRecordStore.clear()
            return
        }

        switch ArmedRecoveryPolicy.decide(record: record, now: now) {
        case .nothing:
            break
        case .rearm:
            try? await session.arm(alarm, now: now)
        case .presentRinging:
            systemRingingAlarmID = alarm.id
        case .repairSchedule:
            ArmedRecordStore.clear()
            try? await AlarmScheduler.cancel(alarm)
            if !alarm.repeatDays.isEmpty {
                try? await AlarmScheduler.schedule(alarm)
            }
        }
    }
}
