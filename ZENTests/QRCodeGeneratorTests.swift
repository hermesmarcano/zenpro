import Testing
import UIKit
@testable import ZEN

struct QRCodeGeneratorTests {
    @Test func generatesNonNilImageForValidPayload() {
        let image = QRCodeGenerator.image(for: "zen://anchor/test/abc123")
        #expect(image != nil)
    }

    @Test func imageHasPositiveSize() {
        let image = QRCodeGenerator.image(for: "zen://anchor/test/abc123")
        #expect((image?.size.width ?? 0) > 0)
        #expect((image?.size.height ?? 0) > 0)
    }
}

struct QRQuietZoneAndPrintableTests {
    @Test func quietZoneAddsFourModulesOnEachSide() throws {
        let scale: CGFloat = 10
        let bare = try #require(QRCodeGenerator.image(for: "zen://anchor/x/y", scale: scale, quietZoneModules: 0))
        let padded = try #require(QRCodeGenerator.image(for: "zen://anchor/x/y", scale: scale, quietZoneModules: 4))
        #expect(padded.size.width == bare.size.width + 2 * 4 * scale)
        #expect(padded.size.height == bare.size.height + 2 * 4 * scale)
    }

    @Test func quietZoneDefaultsToTheSpecMinimum() throws {
        let scale: CGFloat = 10
        let bare = try #require(QRCodeGenerator.image(for: "zen://anchor/x/y", scale: scale, quietZoneModules: 0))
        let byDefault = try #require(QRCodeGenerator.image(for: "zen://anchor/x/y", scale: scale))
        #expect(byDefault.size.width == bare.size.width + 80)
    }

    @Test func negativeQuietZoneIsTreatedAsNone() throws {
        let bare = try #require(QRCodeGenerator.image(for: "zen://anchor/x/y", quietZoneModules: 0))
        let negative = try #require(QRCodeGenerator.image(for: "zen://anchor/x/y", quietZoneModules: -3))
        #expect(negative.size == bare.size)
    }

    @Test func printableSheetIsPortraitAndContainsContent() throws {
        let qr = try #require(QRCodeGenerator.image(for: "zen://anchor/x/y"))
        let sheet = try #require(PrintableAnchor.image(label: "Espejo del baño", backupCode: "042917", qr: qr))
        #expect(sheet.size.height > sheet.size.width)
        #expect(sheet.size.width == 1_200)
    }

    @Test func printableSheetRejectsAnEmptyQR() {
        #expect(PrintableAnchor.image(label: "x", backupCode: "000000", qr: UIImage()) == nil)
    }
}
