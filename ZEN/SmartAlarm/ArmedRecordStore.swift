import Foundation

/// Persiste que la alarma inteligente está armada, para detectar al abrir la app que iOS
/// (o el usuario) la cerró sin terminar la sesión. Ver `ArmedRecoveryPolicy`.
enum ArmedRecordStore {
    private static let key = "zen.armedRecord"

    static func load(defaults: UserDefaults = .standard) -> ArmedRecord? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(ArmedRecord.self, from: data)
    }

    static func save(_ record: ArmedRecord, defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        defaults.set(data, forKey: key)
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
    }
}
