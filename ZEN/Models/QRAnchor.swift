import Foundation
import SwiftData

@Model
final class QRAnchor {
    var id: UUID
    var label: String
    var secretToken: String
    var backupCode: String
    var createdAt: Date
    var confirmedAt: Date?

    init(id: UUID = UUID(), label: String) {
        self.id = id
        self.label = label
        self.secretToken = QRAnchor.generateSecretToken()
        self.backupCode = QRAnchor.generateBackupCode()
        self.createdAt = Date()
        self.confirmedAt = nil
    }

    /// 128 bits de aleatoriedad segura. No se transmite a ningún servidor — todo
    /// el flujo de validación es local, no hay backend en el MVP.
    static func generateSecretToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        let result = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(result == errSecSuccess, "No se pudo generar aleatoriedad segura")
        return Data(bytes).base64EncodedString()
    }

    /// Código numérico de 6 dígitos impreso junto al QR físico: la vía de emergencia
    /// si falla la cámara (edge case de seguridad documentado en ARCHITECTURE.md §5).
    static func generateBackupCode() -> String {
        String(format: "%06d", Int.random(in: 0...999_999))
    }

    var qrPayload: String {
        "zen://anchor/\(id.uuidString)/\(secretToken)"
    }

    func matches(scannedPayload: String) -> Bool {
        scannedPayload == qrPayload
    }

    func matches(enteredBackupCode: String) -> Bool {
        enteredBackupCode == backupCode
    }
}
