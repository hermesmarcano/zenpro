import Foundation

/// Un punto de desactivación ya confirmado, sea un QR impreso o una foto de un lugar.
/// `AlarmConfig.anchorID` apunta a cualquiera de los dos (los UUID no colisionan).
enum AnchorRef: Identifiable {
    case qr(QRAnchor)
    case photo(PhotoAnchor)

    var id: UUID {
        switch self {
        case .qr(let anchor): anchor.id
        case .photo(let anchor): anchor.id
        }
    }

    var label: String {
        switch self {
        case .qr(let anchor): anchor.label
        case .photo(let anchor): anchor.label
        }
    }

    var kindName: String {
        switch self {
        case .qr: "QR"
        case .photo: "Foto"
        }
    }

    var confirmedAt: Date? {
        switch self {
        case .qr(let anchor): anchor.confirmedAt
        case .photo(let anchor): anchor.confirmedAt
        }
    }

    func matches(enteredBackupCode code: String) -> Bool {
        switch self {
        case .qr(let anchor): anchor.matches(enteredBackupCode: code)
        case .photo(let anchor): anchor.matches(enteredBackupCode: code)
        }
    }
}
