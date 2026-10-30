import Testing
import Foundation
import SwiftData
@testable import ZEN

@MainActor
struct PhotoAnchorStoreTests {
    private func makeContext() throws -> ModelContext {
        let container = try ModelContainer(
            for: QRAnchor.self, PhotoAnchor.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        return ModelContext(container)
    }

    @Test func referenceImagesRoundTripThroughSwiftData() throws {
        let context = try makeContext()
        let store = AnchorStore(context: context)
        let anchor = store.pendingOrNewPhotoAnchor(label: "Patio")
        anchor.referenceImages = [Data([1, 2, 3]), Data([4, 5, 6, 7])]
        try context.save()

        let fetched = try context.fetch(FetchDescriptor<PhotoAnchor>())
        #expect(fetched.count == 1)
        #expect(fetched.first?.referenceImages == [Data([1, 2, 3]), Data([4, 5, 6, 7])])
    }

    @Test func reusingPendingPhotoAnchorResetsItsReferences() throws {
        let context = try makeContext()
        let store = AnchorStore(context: context)
        let first = store.pendingOrNewPhotoAnchor(label: "Patio")
        first.referenceImages = [Data([1])]

        let second = store.pendingOrNewPhotoAnchor(label: "Terraza")
        #expect(second.id == first.id)
        #expect(second.label == "Terraza")
        #expect(second.referenceImages.isEmpty)
    }

    @Test func canAddReferenceStopsAtTheMaximum() {
        let anchor = PhotoAnchor(label: "Patio")
        for _ in 0..<PhotoAnchor.maxReferences {
            #expect(anchor.canAddReference)
            anchor.referenceImages.append(Data([0]))
        }
        #expect(!anchor.canAddReference)
    }

    @Test func pendingAnchorsAreNotResolvableForAlarms() throws {
        let context = try makeContext()
        let store = AnchorStore(context: context)
        let pending = store.pendingOrNewPhotoAnchor(label: "Patio")
        #expect(store.anchorRef(id: pending.id) == nil)

        store.confirm(pending)
        guard case .photo(let resolved)? = store.anchorRef(id: pending.id) else {
            Issue.record("Se esperaba resolver el ancla de foto confirmada")
            return
        }
        #expect(resolved.id == pending.id)
    }

    @Test func resolvesBothKindsAndListsThemNewestFirst() throws {
        let context = try makeContext()
        let store = AnchorStore(context: context)

        let qr = store.pendingOrNewAnchor(label: "Baño")
        store.confirm(qr)
        qr.confirmedAt = Date(timeIntervalSince1970: 1_000)

        let photo = store.pendingOrNewPhotoAnchor(label: "Patio")
        store.confirm(photo)
        photo.confirmedAt = Date(timeIntervalSince1970: 2_000)

        let refs = store.confirmedAnchorRefs()
        #expect(refs.map(\.label) == ["Patio", "Baño"])
        #expect(refs.map(\.kindName) == ["Foto", "QR"])

        guard case .qr? = store.anchorRef(id: qr.id) else {
            Issue.record("Se esperaba resolver el ancla QR")
            return
        }
    }

    @Test func unknownIDResolvesToNothing() throws {
        let context = try makeContext()
        #expect(AnchorStore(context: context).anchorRef(id: UUID()) == nil)
    }

    @Test func backupCodeWorksForPhotoAnchors() {
        let anchor = PhotoAnchor(label: "Patio")
        #expect(anchor.backupCode.count == 6)
        #expect(anchor.matches(enteredBackupCode: anchor.backupCode))
        let wrong = anchor.backupCode == "000000" ? "111111" : "000000"
        #expect(!anchor.matches(enteredBackupCode: wrong))
        #expect(AnchorRef.photo(anchor).matches(enteredBackupCode: anchor.backupCode))
    }
}
