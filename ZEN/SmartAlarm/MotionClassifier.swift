import Foundation

struct MotionSample: Equatable {
    var timestamp: TimeInterval
    /// Magnitud de la aceleración del usuario (sin gravedad), en g.
    var userAcceleration: Double
    /// Magnitud de la velocidad de rotación, en rad/s.
    var rotationRate: Double
}

enum MotionState: Equatable {
    case still
    case moving
}

/// Clasifica el flujo de muestras de movimiento en "quieto" / "en movimiento" con
/// histéresis, para que el ruido del sensor o un golpe suelto no hagan oscilar el
/// volumen. Es lógica pura: la lectura real de CoreMotion vive en `MotionMonitor`.
///
/// Los umbrales de `Tuning` son estimaciones razonables sin medir en un dispositivo
/// real [CALIBRAR EN DISPOSITIVO].
struct MotionClassifier {
    struct Tuning: Equatable {
        var windowDuration: TimeInterval = 1.5
        /// Aceleración RMS (g) que, sola, cuenta como un punto de "energía".
        var accelerationScale: Double = 0.04
        /// Rotación RMS (rad/s) que, sola, cuenta como un punto de "energía".
        var rotationScale: Double = 0.2
        var movingScore: Double = 1.0
        var stillScore: Double = 0.4
    }

    private struct Entry {
        var timestamp: TimeInterval
        var accelerationSquared: Double
        var rotationSquared: Double
    }

    private(set) var state: MotionState = .still
    private var window: [Entry] = []
    private let tuning: Tuning

    init(tuning: Tuning = Tuning()) {
        self.tuning = tuning
    }

    mutating func reset() {
        state = .still
        window.removeAll()
    }

    @discardableResult
    mutating func ingest(_ sample: MotionSample) -> MotionState {
        // Si el reloj de los sensores retrocede (reinicio de CoreMotion) las entradas
        // viejas nunca caducarían: se descarta la ventana en vez de arrastrarlas.
        if let last = window.last, sample.timestamp < last.timestamp {
            window.removeAll()
        }

        window.append(Entry(
            timestamp: sample.timestamp,
            accelerationSquared: sample.userAcceleration * sample.userAcceleration,
            rotationSquared: sample.rotationRate * sample.rotationRate
        ))
        let cutoff = sample.timestamp - tuning.windowDuration
        window.removeAll { $0.timestamp < cutoff }

        let count = Double(window.count)
        let accelerationRMS = (window.reduce(0) { $0 + $1.accelerationSquared } / count).squareRoot()
        let rotationRMS = (window.reduce(0) { $0 + $1.rotationSquared } / count).squareRoot()
        let score = accelerationRMS / tuning.accelerationScale + rotationRMS / tuning.rotationScale

        switch state {
        case .still where score >= tuning.movingScore:
            state = .moving
        case .moving where score <= tuning.stillScore:
            state = .still
        default:
            break
        }
        return state
    }
}
