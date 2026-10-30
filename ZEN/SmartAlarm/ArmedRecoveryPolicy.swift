import Foundation

/// Registro persistido de que la alarma inteligente estaba armada.
struct ArmedRecord: Codable, Equatable {
    var alarmID: UUID
    var fireDate: Date
}

/// Qué hacer al abrir la app si quedó un registro de "armada". Si la sesión terminó
/// con normalidad el registro ya no existe; que exista al arrancar significa que iOS (o
/// el usuario) cerró ZEN con la alarma armada, y la alarma del sistema quedó desplazada
/// `hora + 2 min`.
enum ArmedRecoveryPolicy {
    enum Action: Equatable {
        case nothing
        /// Aún no era la hora: volver a armar (restaura audio, aviso y red de seguridad).
        case rearm
        /// La hora pasó hace poco: la alarma pudo haber sonado sin que se demostrara
        /// presencia, así que se pide el QR/foto. Si ya se apagó con "Detener" del
        /// sistema, ZEN lo sigue pidiendo (apagar sin ancla no se pasa por alto).
        case presentRinging
        /// Pasó hace mucho: solo se restaura la hora normal de la alarma del sistema.
        case repairSchedule
    }

    /// Ventana tras la hora de disparo en la que se considera "sin resolver".
    static let unresolvedWindow: TimeInterval = 30 * 60

    static func decide(record: ArmedRecord?, now: Date) -> Action {
        guard let record else { return .nothing }
        if now < record.fireDate {
            return .rearm
        }
        if now.timeIntervalSince(record.fireDate) <= unresolvedWindow {
            return .presentRinging
        }
        return .repairSchedule
    }
}

/// Compuerta del código de respaldo. Sin fricción sería un atajo: el código se puede
/// memorizar y teclear desde la cama, anulando el propósito de la alarma. Solo se
/// habilita tras una espera desde que aparece la pantalla de apagado; el tiempo es
/// monotónico (`systemUptime`) para que cambiar la hora del sistema no lo salte.
enum BackupCodePolicy {
    static let delay: TimeInterval = 120
    static let length = 6

    static func remaining(since start: TimeInterval, now: TimeInterval) -> TimeInterval {
        let elapsed = now - start
        // Un reloj monotónico no retrocede; si lo hiciera, se aplica la espera completa.
        guard elapsed >= 0 else { return delay }
        return max(0, delay - elapsed)
    }

    static func isAvailable(since start: TimeInterval, now: TimeInterval) -> Bool {
        remaining(since: start, now: now) == 0
    }

    /// Solo dígitos ASCII, como máximo `length`.
    static func sanitize(_ input: String) -> String {
        String(input.filter { ("0"..."9").contains($0) }.prefix(length))
    }

    /// "1:05" para una cuenta atrás.
    static func countdownText(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.rounded(.up))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
