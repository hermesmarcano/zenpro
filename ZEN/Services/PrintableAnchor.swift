import UIKit

/// Hoja lista para imprimir: el QR, el nombre del lugar y el código de respaldo.
/// Imprimir el código **junto al QR** es lo que lo mantiene útil sin ser un atajo: solo
/// está a la vista estando en el sitio del ancla (y en pantalla solo tras una espera).
enum PrintableAnchor {
    static func image(label: String, backupCode: String, qr: UIImage) -> UIImage? {
        let pageSize = CGSize(width: 1_200, height: 1_600)
        guard qr.size.width > 0, qr.size.height > 0 else { return nil }

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: pageSize, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: pageSize))

            let center = NSMutableParagraphStyle()
            center.alignment = .center
            center.lineBreakMode = .byTruncatingTail

            func draw(_ text: String, font: UIFont, color: UIColor, y: CGFloat, height: CGFloat) {
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: font, .foregroundColor: color, .paragraphStyle: center,
                ]
                text.draw(in: CGRect(x: 80, y: y, width: pageSize.width - 160, height: height), withAttributes: attributes)
            }

            draw(label, font: .systemFont(ofSize: 76, weight: .bold), color: .black, y: 110, height: 100)

            let side: CGFloat = 900
            qr.draw(in: CGRect(x: (pageSize.width - side) / 2, y: 260, width: side, height: side))

            draw("Código de respaldo", font: .systemFont(ofSize: 44), color: .darkGray, y: 1_230, height: 60)
            draw(backupCode, font: .monospacedSystemFont(ofSize: 110, weight: .semibold), color: .black, y: 1_300, height: 140)
            draw("ZEN · solo úsalo si falla la cámara", font: .systemFont(ofSize: 34), color: .gray, y: 1_480, height: 50)
        }
    }
}
