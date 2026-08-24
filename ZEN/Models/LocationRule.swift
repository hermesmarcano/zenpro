import Foundation
import SwiftData
import CoreLocation

/// Tope de reglas de ubicación configurables en el MVP. iOS limita el monitoreo de
/// regiones a ~20 por app en total; nos quedamos lejos de ese límite a propósito
/// (ver ARCHITECTURE.md §5).
let maxLocationRules = 8

@Model
final class LocationRule {
    var id: UUID
    var label: String
    var latitude: Double
    var longitude: Double
    var radiusMeters: Double
    var isUnlockZone: Bool

    init(
        id: UUID = UUID(),
        label: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        isUnlockZone: Bool
    ) {
        self.id = id
        self.label = label
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
        self.isUnlockZone = isUnlockZone
    }

    var region: CLCircularRegion {
        let region = CLCircularRegion(
            center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            radius: radiusMeters,
            identifier: id.uuidString
        )
        region.notifyOnEntry = true
        region.notifyOnExit = true
        return region
    }
}
