import Foundation
import SwiftData

/// Punto de desactivación identificado por fotos (ej. "Patio") en vez de un QR
/// impreso. Para apagar la alarma hay que tomar otra foto del mismo lugar.
@Model
final class PhotoAnchor {
    static let maxReferences = 3

    var id: UUID
    var label: String
    /// JPEG normalizados (orientación corregida, ≤800 px) de 1–3 fotos de referencia.
    /// Se guardan las imágenes y no los feature prints: se recalculan al comparar, así
    /// una revisión distinta de Vision en una versión futura de iOS no invalida el ancla.
    var referenceImages: [Data]
    var backupCode: String
    var createdAt: Date
    var confirmedAt: Date?

    init(id: UUID = UUID(), label: String) {
        self.id = id
        self.label = label
        self.referenceImages = []
        self.backupCode = QRAnchor.generateBackupCode()
        self.createdAt = Date()
        self.confirmedAt = nil
    }

    var canAddReference: Bool {
        referenceImages.count < PhotoAnchor.maxReferences
    }

    func matches(enteredBackupCode: String) -> Bool {
        enteredBackupCode == backupCode
    }
}
