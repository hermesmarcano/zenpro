import AudioToolbox
import Foundation

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

    static let safetyNetMinutes = 2

    @Published private(set) var state: State = .idle

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

        armedAlarm = alarm
        state = .armed(alarmID: alarm.id, fireDate: fireDate)
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
        WakeNotification.cancel()

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

    private static func timeString(_ alarm: AlarmConfig) -> String {
        String(format: "%02d:%02d", alarm.hour, alarm.minute)
    }
}
