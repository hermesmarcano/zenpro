import SwiftUI

struct RootView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("ZEN")
                .font(.system(.largeTitle, design: .monospaced))
                .fontWeight(.semibold)
            Text("Fundaciones del proyecto — M0")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
        .foregroundStyle(Color.white)
        .ignoresSafeArea()
    }
}

#Preview {
    RootView()
}
