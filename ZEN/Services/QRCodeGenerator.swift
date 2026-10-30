import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

enum QRCodeGenerator {
    /// Genera una imagen de QR a partir de un payload de texto. El filtro de
    /// CoreImage produce una imagen pequeña por defecto; la escalamos sin
    /// interpolación para mantener los bordes nítidos.
    ///
    /// `quietZoneModules`: margen claro alrededor, en módulos. La especificación de QR
    /// pide 4; el filtro de CoreImage no lo incluye y sobre un fondo oscuro (modo oscuro,
    /// o un papel pegado en una pared oscura) los lectores fallan sin ese margen.
    static func image(for payload: String, scale: CGFloat = 10, quietZoneModules: Int = 4) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        let context = CIContext()
        guard let cgImage = context.createCGImage(transformed, from: transformed.extent) else { return nil }
        let code = UIImage(cgImage: cgImage)

        let margin = CGFloat(max(0, quietZoneModules)) * scale
        guard margin > 0 else { return code }

        let size = CGSize(width: code.size.width + 2 * margin, height: code.size.height + 2 * margin)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { rendererContext in
            UIColor.white.setFill()
            rendererContext.fill(CGRect(origin: .zero, size: size))
            rendererContext.cgContext.interpolationQuality = .none
            code.draw(in: CGRect(origin: CGPoint(x: margin, y: margin), size: code.size))
        }
    }
}
