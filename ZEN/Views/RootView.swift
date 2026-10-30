import SwiftUI

/// Punto de entrada. Se mantiene como delgado envoltorio para que el dashboard de M5
/// reemplace `HomeView` sin tocar `ZENApp`.
struct RootView: View {
    var body: some View {
        HomeView()
    }
}

#Preview {
    RootView()
        .environmentObject(AlarmCoordinator.shared)
        .modelContainer(for: [AlarmConfig.self, QRAnchor.self, PhotoAnchor.self], inMemory: true)
}
