import AVFoundation

/// Reproduce el tono de la alarma (o silencio, para mantener viva la app en segundo
/// plano mientras está armada) y permite cambiar el volumen en tiempo real, algo que
/// la alarma del sistema (AlarmKit) no permite.
///
/// Es una capa fina sobre AVAudioEngine; toda la política de volumen vive en
/// `SmartVolumeController`. Solo se puede validar en dispositivo real.
@MainActor
final class AlarmTonePlayer {
    enum Mode: Equatable {
        case stopped
        /// Sesión de audio viva con un bucle de silencio: la app sigue ejecutándose en
        /// segundo plano (`UIBackgroundModes: audio`) hasta la hora de la alarma.
        case silent
        case tone
    }

    private(set) var mode: Mode = .stopped

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let toneBuffer: AVAudioPCMBuffer
    private let silentBuffer: AVAudioPCMBuffer
    private var observers: [NSObjectProtocol] = []
    /// Último modo pedido por el llamador; se restaura tras una interrupción.
    private var desiredMode: Mode = .stopped

    private static let sampleRate: Double = 44_100

    init?() {
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Self.sampleRate,
            channels: 1,
            interleaved: false
        ),
        let tone = Self.makeBuffer(samples: AlarmToneSynth.samples(sampleRate: Self.sampleRate), format: format),
        let silence = Self.makeBuffer(samples: AlarmToneSynth.silence(sampleRate: Self.sampleRate), format: format)
        else {
            return nil
        }
        toneBuffer = tone
        silentBuffer = silence

        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        observeInterruptions()
    }

    func play(_ newMode: Mode) throws {
        desiredMode = newMode
        guard newMode != .stopped else {
            stopPlayback()
            return
        }

        // El silencio de "armada" no debe cortar el podcast o los sonidos de dormir
        // que el usuario tenga puestos; el tono de la alarma sí tiene que imponerse.
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: newMode == .silent ? [.mixWithOthers] : [])
        try session.setActive(true)

        player.stop()
        if !engine.isRunning {
            try engine.start()
        }
        player.scheduleBuffer(newMode == .tone ? toneBuffer : silentBuffer, at: nil, options: .loops)
        player.play()
        mode = newMode
    }

    /// `volume` entre 0 y 1, relativo al volumen del sistema.
    func setVolume(_ volume: Float) {
        player.volume = min(max(volume, 0), 1)
    }

    func stop() {
        desiredMode = .stopped
        stopPlayback()
    }

    private func stopPlayback() {
        player.stop()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        mode = .stopped
    }

    // MARK: - Interrupciones

    /// Llamadas, Siri u otra app pueden detener el motor. Al terminar la interrupción
    /// se retoma el último modo pedido; sin esto la alarma se quedaría muda.
    private func observeInterruptions() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            MainActor.assumeIsolated {
                guard let self,
                      let typeValue,
                      AVAudioSession.InterruptionType(rawValue: typeValue) == .ended else { return }
                self.resume()
            }
        })
        observers.append(center.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.resume() }
        })
    }

    private func resume() {
        guard desiredMode != .stopped else { return }
        try? play(desiredMode)
    }

    private static func makeBuffer(samples: [Float], format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else {
            return nil
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { source in
            if let base = source.baseAddress {
                channel.update(from: base, count: samples.count)
            }
        }
        return buffer
    }
}
