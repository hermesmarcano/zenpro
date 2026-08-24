import Testing
@testable import ZEN

struct QRAnchorTests {
    @Test func payloadEncodesIdAndSecret() {
        let anchor = QRAnchor(label: "Espejo del baño")
        #expect(anchor.qrPayload.contains(anchor.id.uuidString))
        #expect(anchor.qrPayload.contains(anchor.secretToken))
    }

    @Test func matchesOwnPayload() {
        let anchor = QRAnchor(label: "Cocina")
        #expect(anchor.matches(scannedPayload: anchor.qrPayload))
    }

    @Test func rejectsForeignPayload() {
        let anchor = QRAnchor(label: "Cocina")
        let other = QRAnchor(label: "Otro")
        #expect(!anchor.matches(scannedPayload: other.qrPayload))
    }

    @Test func backupCodeIsSixDigits() {
        let anchor = QRAnchor(label: "Baño")
        #expect(anchor.backupCode.count == 6)
        #expect(anchor.backupCode.allSatisfy(\.isNumber))
    }

    @Test func matchesOwnBackupCode() {
        let anchor = QRAnchor(label: "Baño")
        #expect(anchor.matches(enteredBackupCode: anchor.backupCode))
    }

    @Test func rejectsForeignBackupCode() {
        let anchor = QRAnchor(label: "Baño")
        let wrong = anchor.backupCode == "000000" ? "111111" : "000000"
        #expect(!anchor.matches(enteredBackupCode: wrong))
    }
}
