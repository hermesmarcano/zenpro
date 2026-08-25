import SwiftUI

struct AlarmConfirmedView: View {
    var onContinue: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "checkmark.circle")
                .font(.system(size: 44))
                .foregroundStyle(Color(red: 0.61, green: 0.72, blue: 0.59))
            Text("Cadena rota.")
                .font(.system(.title, design: .rounded).bold())
            Text("Estás de pie. Ahora, antes de tocar cualquier otra cosa, decide qué es lo único que de verdad importa hoy.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)
            Spacer()
            Button("Definir mi tarea del día") {
                onContinue()
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 32)
            .padding(.bottom, 32)
        }
    }
}

#Preview {
    AlarmConfirmedView(onContinue: {})
}
