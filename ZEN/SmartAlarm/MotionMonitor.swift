import CoreMotion

/// Lee el movimiento del teléfono con CoreMotion y lo reduce a quieto/en movimiento
/// con `MotionClassifier`. `deviceMotion` no requiere permiso del usuario. Mientras la
/// app tenga una sesión de audio activa en segundo plano, las actualizaciones siguen
/// llegando con la pantalla bloqueada.
final class MotionMonitor {
    private let manager = CMMotionManager()
    private let queue: OperationQueue
    private let lock = NSLock()
    private var classifier = MotionClassifier()
    private var latest: MotionState = .still

    init() {
        queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "zen.motion"
    }

    /// Sin sensor (Simulator) el estado queda siempre en `.still`: el volumen solo
    /// sigue la rampa por tiempo.
    var isAvailable: Bool { manager.isDeviceMotionAvailable }

    var state: MotionState {
        lock.lock()
        defer { lock.unlock() }
        return latest
    }

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }

        lock.lock()
        classifier.reset()
        latest = .still
        lock.unlock()

        manager.deviceMotionUpdateInterval = 1.0 / 25.0
        manager.startDeviceMotionUpdates(to: queue) { [weak self] motion, _ in
            guard let self, let motion else { return }
            let acceleration = motion.userAcceleration
            let rotation = motion.rotationRate
            let sample = MotionSample(
                timestamp: motion.timestamp,
                userAcceleration: (acceleration.x * acceleration.x
                    + acceleration.y * acceleration.y
                    + acceleration.z * acceleration.z).squareRoot(),
                rotationRate: (rotation.x * rotation.x
                    + rotation.y * rotation.y
                    + rotation.z * rotation.z).squareRoot()
            )
            self.lock.lock()
            self.latest = self.classifier.ingest(sample)
            self.lock.unlock()
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
    }
}
