import Foundation
import SwiftData

extension AnchorStore {
    /// Como `pendingOrNewAnchor`, pero para fotos. Si se reutiliza el ancla pendiente
    /// se descartan sus referencias: reiniciar el alta empieza de cero en vez de
    /// mezclar fotos de dos intentos distintos.
    func pendingOrNewPhotoAnchor(label: String) -> PhotoAnchor {
        var descriptor = FetchDescriptor<PhotoAnchor>(
            predicate: #Predicate { $0.confirmedAt == nil },
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        descriptor.fetchLimit = 1

        if let existing = try? context.fetch(descriptor).first {
            existing.label = label
            existing.referenceImages = []
            return existing
        }

        let anchor = PhotoAnchor(label: label)
        context.insert(anchor)
        return anchor
    }

    func confirm(_ anchor: PhotoAnchor) {
        anchor.confirmedAt = Date()
    }

    func confirmedPhotoAnchors() -> [PhotoAnchor] {
        let descriptor = FetchDescriptor<PhotoAnchor>(
            predicate: #Predicate { $0.confirmedAt != nil },
            sortBy: [SortDescriptor(\.confirmedAt, order: .reverse)]
        )
        return (try? context.fetch(descriptor)) ?? []
    }

    /// Todos los anclas confirmadas (QR y foto), la más reciente primero.
    func confirmedAnchorRefs() -> [AnchorRef] {
        let refs = confirmedAnchors().map(AnchorRef.qr) + confirmedPhotoAnchors().map(AnchorRef.photo)
        return refs.sorted { ($0.confirmedAt ?? .distantPast) > ($1.confirmedAt ?? .distantPast) }
    }

    /// Resuelve el ancla de una alarma. Solo devuelve anclas confirmadas: una pendiente
    /// (a medio configurar) no debe poder apagar una alarma.
    func anchorRef(id: UUID) -> AnchorRef? {
        let qrDescriptor = FetchDescriptor<QRAnchor>(
            predicate: #Predicate { $0.id == id && $0.confirmedAt != nil }
        )
        if let anchor = try? context.fetch(qrDescriptor).first {
            return .qr(anchor)
        }
        let photoDescriptor = FetchDescriptor<PhotoAnchor>(
            predicate: #Predicate { $0.id == id && $0.confirmedAt != nil }
        )
        if let anchor = try? context.fetch(photoDescriptor).first {
            return .photo(anchor)
        }
        return nil
    }
}
