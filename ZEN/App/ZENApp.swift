import SwiftUI
import SwiftData

@main
struct ZENApp: App {
    let container: ModelContainer

    init() {
        do {
            container = try ModelContainer(
                for: AlarmConfig.self,
                QRAnchor.self,
                BlockRuleSet.self,
                LocationRule.self,
                TaskItem.self,
                UnlockRequest.self
            )
        } catch {
            // Un almacenamiento local corrupto antes de poder mostrar cualquier UI no es
            // recuperable en M0. La recuperación (reconstruir el store, avisar al usuario)
            // es un edge case de endurecimiento que se aborda en M6, no aquí.
            fatalError("No se pudo inicializar el almacenamiento local de ZEN: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .modelContainer(container)
    }
}
