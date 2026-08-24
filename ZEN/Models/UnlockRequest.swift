import Foundation
import SwiftData

/// Auditoría del flujo de fricción de desbloqueo anticipado (ver ARCHITECTURE.md §4.4).
@Model
final class UnlockRequest {
    var id: UUID
    var requestedAt: Date
    var reason: String
    var cooldownEndsAt: Date
    var grantedAt: Date?

    init(
        id: UUID = UUID(),
        reason: String,
        cooldown: TimeInterval,
        requestedAt: Date = Date()
    ) {
        self.id = id
        self.requestedAt = requestedAt
        self.reason = reason
        self.cooldownEndsAt = requestedAt.addingTimeInterval(cooldown)
        self.grantedAt = nil
    }
}
