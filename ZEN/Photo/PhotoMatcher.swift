import UIKit
import Vision

enum PhotoNormalizer {
    static let maxDimension: CGFloat = 800

    /// Devuelve la imagen con la orientación aplicada (los píxeles quedan "derechos") y
    /// reducida a `maxDimension`. Referencia y candidata pasan siempre por aquí: así la
    /// comparación no depende de cómo guardó la cámara la orientación EXIF.
    static func normalized(_ image: UIImage) -> UIImage? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }

        let scale = min(1, maxDimension / max(size.width, size.height))
        let target = CGSize(
            width: max(1, (size.width * scale).rounded()),
            height: max(1, (size.height * scale).rounded())
        )

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    static func jpegData(from image: UIImage, quality: CGFloat = 0.7) -> Data? {
        image.jpegData(compressionQuality: quality)
    }
}

enum PhotoMatchError: Error {
    case noFeaturePrint
}

/// Calcula distancias de feature print con Vision. La decisión de aceptar o rechazar
/// vive en `PhotoMatchPolicy`.
enum PhotoMatcher {
    /// Una distancia por referencia. Los feature prints se calculan siempre en la misma
    /// ejecución (candidata y referencias), por lo que comparten revisión de Vision.
    static func distances(candidate: CGImage, references: [CGImage]) throws -> [Float] {
        let candidatePrint = try featurePrint(for: candidate)
        return try references.map { reference in
            let referencePrint = try featurePrint(for: reference)
            var distance: Float = 0
            try candidatePrint.computeDistance(&distance, to: referencePrint)
            return distance
        }
    }

    private static func featurePrint(for image: CGImage) throws -> VNFeaturePrintObservation {
        let request = VNGenerateImageFeaturePrintRequest()
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        try handler.perform([request])
        guard let observation = request.results?.first as? VNFeaturePrintObservation else {
            throw PhotoMatchError.noFeaturePrint
        }
        return observation
    }
}

enum PhotoVerifier {
    struct Outcome: Equatable {
        var bestDistance: Float
        var isMatch: Bool
    }

    /// `nil` significa que no se pudo analizar la foto (fallo de Vision o imagen
    /// ilegible) — distinto de "no coincide", para que la pantalla no culpe al usuario
    /// de un error técnico ni cuente el intento como fallo.
    static func verify(
        candidate: UIImage,
        references: [Data],
        threshold: Float = PhotoMatchPolicy.defaultThreshold
    ) async -> Outcome? {
        await Task.detached(priority: .userInitiated) { () -> Outcome? in
            guard let candidateImage = PhotoNormalizer.normalized(candidate)?.cgImage else { return nil }
            let referenceImages = references.compactMap { UIImage(data: $0)?.cgImage }
            guard !referenceImages.isEmpty,
                  let distances = try? PhotoMatcher.distances(candidate: candidateImage, references: referenceImages),
                  let best = PhotoMatchPolicy.bestDistance(distances) else {
                return nil
            }
            return Outcome(
                bestDistance: best,
                isMatch: PhotoMatchPolicy.isMatch(distances: distances, threshold: threshold)
            )
        }.value
    }
}
