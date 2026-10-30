import Foundation

/// Parámetros de la curva de volumen. Los valores por defecto codifican el
/// comportamiento pedido: arranca bajo; al coger el teléfono baja a la mitad y,
/// mientras hay movimiento, a ~10 %; si se deja quieto sin apagar vuelve al 50 % y
/// sube progresivamente a 100 %.
struct SmartVolumeProfile: Equatable {
    var startVolume: Float = 0.15
    var maxVolume: Float = 1.0
    var initialRampDuration: TimeInterval = 90

    /// Fracción del volumen que se conserva en el instante de coger el teléfono.
    var pickupFactor: Float = 0.5
    /// Tiempo que se mantiene el volumen "a la mitad" antes de bajar al de marcha.
    var pickupHoldDuration: TimeInterval = 1.5
    var carriedVolume: Float = 0.10

    /// Quietud sostenida (sin interactuar) que se interpreta como "lo dejó por ahí".
    var stationaryDelay: TimeInterval = 5
    var leftBehindStartVolume: Float = 0.5
    var leftBehindRampDuration: TimeInterval = 60

    /// Límite de pendiente: ningún cambio de volumen es instantáneo.
    var maxFallPerSecond: Float = 0.35
    var maxRisePerSecond: Float = 0.5

    var slowestVibrationInterval: TimeInterval = 2.0
    var fastestVibrationInterval: TimeInterval = 0.6
}

struct SmartVolumeOutput: Equatable {
    var volume: Float
    /// Segundos entre pulsos de vibración; `nil` = sin vibración.
    var vibrationInterval: TimeInterval?
}

/// Máquina de estados del volumen de la alarma. Es una función determinista del
/// tiempo y de las señales de entrada (sin relojes ni sensores propios), de modo que
/// toda la curva se puede probar en CI sin hardware.
struct SmartVolumeController {
    enum Phase: Equatable {
        case initialRamp
        case carried(since: TimeInterval, volumeAtPickup: Float)
        case leftBehind(since: TimeInterval)
    }

    private(set) var phase: Phase = .initialRamp
    private(set) var volume: Float

    private let profile: SmartVolumeProfile
    private let startTime: TimeInterval
    private var lastUpdate: TimeInterval
    private var stillSince: TimeInterval?

    init(profile: SmartVolumeProfile = SmartVolumeProfile(), startTime: TimeInterval) {
        self.profile = profile
        self.startTime = startTime
        self.lastUpdate = startTime
        self.volume = profile.startVolume
    }

    /// - Parameter userEngaged: el usuario está interactuando con la pantalla de la
    ///   alarma (cámara de escaneo/foto abierta). Se trata como "en mano" aunque el
    ///   teléfono esté quieto; si no, sostenerlo firme para apuntar al QR dispararía
    ///   el estado `leftBehind` y subiría el volumen justo cuando intenta apagarla.
    mutating func update(now rawNow: TimeInterval, motion: MotionState, userEngaged: Bool) -> SmartVolumeOutput {
        // Un reloj que retrocede no debe producir pasos negativos ni fases al revés.
        let now = max(rawNow, lastUpdate)
        let deltaTime = now - lastUpdate
        lastUpdate = now

        advancePhase(now: now, motion: motion, userEngaged: userEngaged)
        applySlew(toward: targetVolume(now: now), deltaTime: deltaTime)
        return SmartVolumeOutput(volume: volume, vibrationInterval: vibrationInterval())
    }

    private mutating func advancePhase(now: TimeInterval, motion: MotionState, userEngaged: Bool) {
        switch phase {
        case .initialRamp, .leftBehind:
            if motion == .moving || userEngaged {
                phase = .carried(since: now, volumeAtPickup: volume)
                stillSince = nil
            }
        case .carried:
            guard motion == .still, !userEngaged else {
                stillSince = nil
                return
            }
            let since = stillSince ?? now
            stillSince = since
            if now - since >= profile.stationaryDelay {
                phase = .leftBehind(since: now)
                stillSince = nil
            }
        }
    }

    private func targetVolume(now: TimeInterval) -> Float {
        switch phase {
        case .initialRamp:
            return interpolate(
                from: profile.startVolume,
                to: profile.maxVolume,
                elapsed: now - startTime,
                duration: profile.initialRampDuration
            )
        case .carried(let since, let volumeAtPickup):
            let halved = max(profile.carriedVolume, volumeAtPickup * profile.pickupFactor)
            return now - since < profile.pickupHoldDuration ? halved : profile.carriedVolume
        case .leftBehind(let since):
            return interpolate(
                from: profile.leftBehindStartVolume,
                to: profile.maxVolume,
                elapsed: now - since,
                duration: profile.leftBehindRampDuration
            )
        }
    }

    private func interpolate(from: Float, to: Float, elapsed: TimeInterval, duration: TimeInterval) -> Float {
        guard duration > 0 else { return to }
        let progress = Float(min(max(elapsed / duration, 0), 1))
        return from + (to - from) * progress
    }

    private mutating func applySlew(toward target: Float, deltaTime: TimeInterval) {
        let step = Float(deltaTime)
        if target > volume {
            volume = min(target, volume + profile.maxRisePerSecond * step)
        } else {
            volume = max(target, volume - profile.maxFallPerSecond * step)
        }
        volume = min(max(volume, 0), 1)
    }

    private func vibrationInterval() -> TimeInterval? {
        if case .carried = phase { return nil }
        let range = profile.maxVolume - profile.startVolume
        let fraction = range > 0 ? Double(min(max((volume - profile.startVolume) / range, 0), 1)) : 1
        let slowest = profile.slowestVibrationInterval
        let fastest = profile.fastestVibrationInterval
        return slowest + (fastest - slowest) * fraction
    }
}
