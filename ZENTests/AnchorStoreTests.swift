import Testing
import SwiftData
@testable import ZEN

@MainActor
struct AnchorStoreTests {
    private func makeContext() throws -> ModelContext {
        let container = try ModelContainer(
            for: QRAnchor.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        return ModelContext(container)
    }

    @Test func createsNewPendingAnchor() throws {
        let context = try makeContext()
        let store = AnchorStore(context: context)
        let anchor = store.pendingOrNewAnchor(label: "Baño")
        #expect(anchor.label == "Baño")
        #expect(anchor.confirmedAt == nil)
    }

    @Test func reusesExistingPendingAnchorInsteadOfDuplicating() throws {
        let context = try makeContext()
        let store = AnchorStore(context: context)
        let first = store.pendingOrNewAnchor(label: "Baño")
        let second = store.pendingOrNewAnchor(label: "Cocina")
        #expect(first.id == second.id)
        #expect(second.label == "Cocina")
    }

    @Test func confirmSetsTimestampAndExcludesFromPending() throws {
        let context = try makeContext()
        let store = AnchorStore(context: context)
        let anchor = store.pendingOrNewAnchor(label: "Baño")
        store.confirm(anchor)
        #expect(anchor.confirmedAt != nil)
        #expect(store.confirmedAnchors().contains { $0.id == anchor.id })

        let stillPending = store.pendingOrNewAnchor(label: "Otro lugar")
        #expect(stillPending.id != anchor.id)
    }
}
