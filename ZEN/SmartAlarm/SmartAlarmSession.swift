import AudioToolbox
import AVFoundation
import Foundation
import UserNotifications

/// Alarma inteligente armada: mientras está armada ZEN mantiene viva una sesión de
/// audio en segundo plano; a la hora, reproduce el tono con la curva de
/// `SmartVolumeController` (la alarma del sistema no permite cambiar su volumen).
///
/// Red de seguridad: al armar, la alarma de AlarmKit se mueve a `hora + 2 min`. Si iOS
/// cierra ZEN durante la noche suena igualmente, con ese retraso. Si el usuario apaga
/// a tiempo con el QR/foto, esa alarma del sistema se cancela.
///
/// Todo lo que toca hardware (audio, sensores, vibración) solo se puede validar en un
/// dispositivo real; las reglas (volumen, movimiento, fechas) están en tipos puros con
/// tests propios.
@MainActor
final class SmartAlarmSession: ObservableObject {
    enum State: Equatable {
        case idle
        case armed(alarmID: UUID, fireDate: Date)
        case ringing(alarmID: UUID)
    }

    enum ArmError: Error {
        case noUpcomingFireDate
        case audioUnavailable
        case alreadyRinging
    }

    /// Cosas que pueden hacer que la alarma armada no se oiga o no se vea; se muestran al
    /// armar, cuando todavía se puede arreglar (a las 6 a. m. ya no).
    enum ArmWarning: Hashable {
        case lowVolume
        case externalAudioRoute
        case notificationsDisabled

        var message: String {
            switch self {
            case .lowVolume:
                "El volumen del teléfono está bajo. ZEN suena con el volumen multimedia: súbelo antes de dormir."
            case .externalAudioRoute:
                "El audio sale por auriculares o Bluetooth, no por el altavoz del teléfono."
            case .notificationsDisabled:
                "Las notificaciones están desactivadas: al sonar tendrás que abrir ZEN a mano."
            }
        }
    }

    static let safetyNetMinutes = 2
    /// Lo que dura el pulso de vibración más su "eco"; el sensor de movimiento se ignora
    /// durante ese tiempo para que la alarma no se detecte a sí misma.
    static let vibrationBlankout: TimeInterval = 0.7

    @Published private(set) var state: State = .idle
    @Published private(set) var armWarnings: [ArmWarning] = []

    private let player = AlarmTonePlayer()
    private let motion = MotionMonitor()
    private let profile = SmartVolumeProfile()
    private var volumeController: SmartVolumeController?
    private var ticker: Timer?
    private var armedAlarm: AlarmConfig?
    private var userEngaged = false
    private var lastVibration: TimeInterval = 0

    var armedAlarmID: UUID? {
        if case .armed(let id, _) = state { id } else { nil }
    }

    var ringingAlarmID: UUID? {
        if case .ringing(let id) = state { id } else { nil }
    }

    var armedFireDate: Date? {
        if case .armed(_, let fireDate) = state { fireDate } else { nil }
    }

    // MARK: - Armar / desarmar

    func arm(_ alarm: AlarmConfig, now: Date = Date()) async throws {
        if ringingAlarmID != nil {
            throw ArmError.alreadyRinging
        }
        if state != .idle {
            await disarm()
        }
        guard let fireDate = NextFireCalculator.nextFireDate(
            after: now,
            hour: alarm.hour,
            minute: alarm.minute,
            weekdays: alarm.repeatDays
        ) else {
            throw ArmError.noUpcomingFireDate
        }
        guard let player else {
            throw ArmError.audioUnavailable
        }

        try player.play(.silent)
        do {
            try await AlarmScheduler.schedule(alarm, delayMinutes: Self.safetyNetMinutes)
        } catch {
            player.stop()
            throw error
        }

        _ = await WakeNotification.requestAuthorization()
        await WakeNotification.schedule(at: fireDate, label: Self.timeString(alarm))
        armWarnings = await Self.currentWarnings()

        armedAlarm = alarm
        state = .armed(alarmID: alarm.id, fireDate: fireDate)
        ArmedRecordStore.save(ArmedRecord(alarmID: alarm.id, fireDate: fireDate))
        startTicker(interval: 1)
    }

    /// El usuario cancela el modo armado: la alarma vuelve a sonar a su hora exacta,
    /// con el sonido normal del sistema.
    func disarm() async {
        guard case .armed = state else { return }
        await teardown(rescheduleSystemAlarm: true)
    }

    /// Tras comprobar presencia (QR/foto): silencia todo y cancela la red de seguridad.
    /// Si la alarma es repetitiva se deja programada con su hora normal para los
    /// próximos días (sin armar: hay que volver a armar cada noche).
    func finishRinging() async {
        guard case .ringing = state else { return }
        let repeats = !(armedAlarm?.repeatDays.isEmpty ?? true)
        await teardown(rescheduleSystemAlarm: repeats)
    }

    func setUserEngaged(_ engaged: Bool) {
        userEngaged = engaged
    }

    /// Se llama al volver la app a primer plano: cubre un temporizador que no corrió
    /// (suspensión, cambio de reloj) y un cambio de zona horaria con la alarma armada.
    func reconcile(now: Date = Date()) {
        guard case .armed(let alarmID, let currentFireDate) = state, let alarm = armedAlarm else { return }
        guard let recomputed = NextFireCalculator.nextFireDate(
            after: now.addingTimeInterval(-1),
            hour: alarm.hour,
            minute: alarm.minute,
            weekdays: alarm.repeatDays
        ) else { return }

        if now >= currentFireDate {
            startRinging()
        } else if recomputed != currentFireDate {
            state = .armed(alarmID: alarmID, fireDate: recomputed)
            ArmedRecordStore.save(ArmedRecord(alarmID: alarmID, fireDate: recomputed))
            Task {
                await WakeNotification.schedule(at: recomputed, label: Self.timeString(alarm))
            }
        }
    }

    // MARK: - Sonando

    private func startRinging() {
        guard case .armed(let alarmID, _) = state else { return }

        // Si el audio falla no se aborta: la red de seguridad de AlarmKit sigue en pie.
        try? player?.play(.tone)
        player?.setVolume(profile.startVolume)
        motion.start()
        volumeController = SmartVolumeController(
            profile: profile,
            startTime: ProcessInfo.processInfo.systemUptime
        )
        lastVibration = 0
        state = .ringing(alarmID: alarmID)
        startTicker(interval: 0.1)
    }

    private func tick() {
        switch state {
        case .idle:
            stopTicker()
        case .armed(_, let fireDate):
            if Date() >= fireDate {
                startRinging()
            }
        case .ringing:
            guard var controller = volumeController else { return }
            let now = ProcessInfo.processInfo.systemUptime
            let output = controller.update(now: now, motion: motion.state, userEngaged: userEngaged)
            volumeController = controller
            player?.setVolume(output.volume)

            if let interval = output.vibrationInterval, now - lastVibration >= interval {
                motion.suppressSamples(for: Self.vibrationBlankout)
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                lastVibration = now
            }
        }
    }

    // MARK: - Utilidades

    private func teardown(rescheduleSystemAlarm: Bool) async {
        stopTicker()
        motion.stop()
        player?.stop()
        volumeController = nil
        userEngaged = false
        armWarnings = []
        WakeNotification.cancel()
        ArmedRecordStore.clear()

        let alarm = armedAlarm
        armedAlarm = nil
        state = .idle

        if let alarm {
            try? await AlarmScheduler.cancel(alarm)
            if rescheduleSystemAlarm {
                try? await AlarmScheduler.schedule(alarm)
            }
        }
    }

    private func startTicker(interval: TimeInterval) {
        stopTicker()
        ticker = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    private func stopTicker() {
        ticker?.invalidate()
        ticker = nil
    }

    private static func currentWarnings() async -> [ArmWarning] {
        var warnings: [ArmWarning] = []

        let audio = AVAudioSession.sharedInstance()
        if audio.outputVolume < 0.4 {
            warnings.append(.lowVolume)
        }
        let builtInPorts: Set<AVAudioSession.Port> = [.builtInSpeaker, .builtInReceiver]
        if audio.currentRoute.outputs.contains(where: { !builtInPorts.contains($0.portType) }) {
            warnings.append(.externalAudioRoute)
        }

        let settings = await UNUserNotificationCenter.current().notificationSettings()
        if settings.authorizationStatus == .denied {
            warnings.append(.notificationsDisabled)
        }
        return warnings
    }

    private static func timeString(_ alarm: AlarmConfig) -> String {
        String(format: "%02d:%02d", alarm.hour, alarm.minute)
    }
}

extension SmartAlarmSession.ArmError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .noUpcomingFireDate: "No se pudo calcular la próxima hora de la alarma."
        case .audioUnavailable: "El audio del teléfono no está disponible para ZEN."
        case .alreadyRinging: "La alarma está sonando: apágala antes de armar otra."
        }
    }
}
