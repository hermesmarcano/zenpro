import Testing
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
