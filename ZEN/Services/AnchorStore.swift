import Foundation
import SwiftData

/// Capa fina sobre SwiftData para crear y confirmar anclas QR. Mantiene como máximo
/// un ancla "pendiente de confirmar" por vez: si el usuario reinicia el paso de
/// configuración del QR, se reutiliza el ancla pendiente en vez de acumular huérfanas.
@MainActor
struct AnchorStore {
    let context: ModelContext

    func pendingOrNewAnchor(label: String) -> QRAnchor {
        var descriptor = FetchDescriptor<QRAnchor>(
            predicate: #Predicate { $0.confirmedAt == nil },
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        descriptor.fetchLimit = 1

        if let existing = try? context.fetch(descriptor).first {
            existing.label = label
            return existing
        }

        let anchor = QRAnchor(label: label)
        context.insert(anchor)
        return anchor
    }

    func confirm(_ anchor: QRAnchor) {
        anchor.confirmedAt = Date()
    }

    func confirmedAnchors() -> [QRAnchor] {
        let descriptor = FetchDescriptor<QRAnchor>(
            predicate: #Predicate { $0.confirmedAt != nil },
            sortBy: [SortDescriptor(\.confirmedAt, order: .reverse)]
        )
        return (try? context.fetch(descriptor)) ?? []
    }
}
