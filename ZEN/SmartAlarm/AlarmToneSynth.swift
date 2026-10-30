import Foundation

/// Sintetiza el tono de la alarma (sin assets binarios ni licencias): cuatro pitidos
/// alternando dos notas, seguidos de un silencio, en un bucle de 2 s. Empieza y
/// termina en cero para que el bucle no tenga clics.
enum AlarmToneSynth {
    static let loopDuration: TimeInterval = 2.0

    private static let beepDuration: TimeInterval = 0.12
    private static let beepStarts: [TimeInterval] = [0.0, 0.2, 0.4, 0.6]
    private static let frequencies: [Double] = [880, 1_175, 880, 1_175]
    private static let envelope: TimeInterval = 0.008
    private static let amplitude: Float = 0.8

    static func samples(sampleRate: Double = 44_100) -> [Float] {
        let frameCount = Int(loopDuration * sampleRate)
        var buffer = [Float](repeating: 0, count: frameCount)

        for (index, start) in beepStarts.enumerated() {
            let firstFrame = Int(start * sampleRate)
            let beepFrames = Int(beepDuration * sampleRate)
            let envelopeFrames = max(1, Int(envelope * sampleRate))
            let frequency = frequencies[index]

            for offset in 0..<beepFrames {
                let frame = firstFrame + offset
                guard frame < frameCount else { break }

                let fadeIn = min(1, Float(offset) / Float(envelopeFrames))
                let fadeOut = min(1, Float(beepFrames - 1 - offset) / Float(envelopeFrames))
                let phase = 2 * Double.pi * frequency * Double(offset) / sampleRate
                buffer[frame] = amplitude * fadeIn * fadeOut * Float(sin(phase))
            }
        }
        return buffer
    }

    /// Silencio del mismo largo, para mantener viva la sesión de audio mientras la
    /// alarma está armada sin que suene nada.
    static func silence(sampleRate: Double = 44_100) -> [Float] {
        [Float](repeating: 0, count: Int(loopDuration * sampleRate))
    }
}
