import SwiftUI
import SwiftData

struct QRAnchorSetupView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var label = ""
    @State private var anchor: QRAnchor?
    @State private var qrImage: UIImage?
    @State private var step: Step = .label
    @State private var backupCodeInput = ""
    @State private var backupCodeError = false

    private enum Step {
        case label
        case showQR
        case scanning
        case cameraDenied
        case backupCode
        case confirmed
    }

    var body: some View {
        VStack(spacing: 24) {
            switch step {
            case .label:
                labelStep
            case .showQR:
                qrStep
            case .scanning:
                scanningStep
            case .cameraDenied:
                cameraDeniedStep
            case .backupCode:
                backupCodeStep
            case .confirmed:
                confirmedStep
            }
        }
        .padding(24)
        .animation(.default, value: step)
    }

    // MARK: - Paso 1: etiqueta del lugar

    private var labelStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("¿Dónde vas a pegar el código?")
                .font(.title2.bold())
            TextField("Ej. Espejo del baño", text: $label)
                .textFieldStyle(.roundedBorder)
            Button("Generar código") {
                generateAnchor()
            }
            .disabled(label.trimmingCharacters(in: .whitespaces).isEmpty)
            .buttonStyle(.borderedProminent)
        }
    }

    private func generateAnchor() {
        let store = AnchorStore(context: modelContext)
        let created = store.pendingOrNewAnchor(label: label.trimmingCharacters(in: .whitespaces))
        anchor = created
        qrImage = QRCodeGenerator.image(for: created.qrPayload)
        step = .showQR
    }

    // MARK: - Paso 2: mostrar QR para imprimir/pegar

    private var qrStep: some View {
        VStack(spacing: 20) {
            Text("Pega este código en un punto fijo, fuera de tu cuarto.")
                .font(.title3.bold())
                .multilineTextAlignment(.center)

            if let qrImage {
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 200, height: 200)
            }

            if let anchor {
                VStack(spacing: 4) {
                    Text("Código de respaldo: \(anchor.backupCode)")
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(.secondary)
                    Text("Guárdalo por si falla la cámara.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Button("Ya lo pegué — confirmar ubicación") {
                beginScanning()
            }
            .buttonStyle(.borderedProminent)

            Button("No puedo escanear ahora") {
                step = .backupCode
            }
            .buttonStyle(.plain)
            .font(.footnote)
        }
    }

    private func beginScanning() {
        Task { @MainActor in
            let granted = await CameraPermission.request()
            step = granted ? .scanning : .cameraDenied
        }
    }

    // MARK: - Paso 3: escaneo real

    private var scanningStep: some View {
        ZStack {
            QRScannerView { payload in
                handleScan(payload)
            }
            .ignoresSafeArea()

            VStack {
                Spacer()
                Button("Usar el código de respaldo") {
                    step = .backupCode
                }
                .buttonStyle(.bordered)
                .padding(.bottom, 40)
            }
        }
    }

    private func handleScan(_ payload: String) {
        guard let anchor, anchor.matches(scannedPayload: payload) else { return }
        confirm(anchor)
    }

    // MARK: - Cámara denegada

    private var cameraDeniedStep: some View {
        VStack(spacing: 16) {
            Text("No tenemos acceso a la cámara.")
                .font(.title3.bold())
            Text("Puedes activarlo en Ajustes, o confirmar con el código de respaldo por ahora.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("Usar código de respaldo") {
                step = .backupCode
            }
            .buttonStyle(.borderedProminent)
        }
    }

    // MARK: - Código de respaldo

    private var backupCodeStep: some View {
        VStack(spacing: 16) {
            Text("Escribe el código de respaldo")
                .font(.title3.bold())
            TextField("000000", text: $backupCodeInput)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.system(.title2, design: .monospaced))
            if backupCodeError {
                Text("Ese código no coincide.")
                    .foregroundStyle(.red)
                    .font(.footnote)
            }
            Button("Confirmar") {
                submitBackupCode()
            }
            .buttonStyle(.borderedProminent)
            .disabled(backupCodeInput.count != 6)
        }
    }

    private func submitBackupCode() {
        guard let anchor, anchor.matches(enteredBackupCode: backupCodeInput) else {
            backupCodeError = true
            return
        }
        confirm(anchor)
    }

    private func confirm(_ anchor: QRAnchor) {
        AnchorStore(context: modelContext).confirm(anchor)
        step = .confirmed
    }

    // MARK: - Confirmado

    private var confirmedStep: some View {
        VStack(spacing: 16) {
            Text("Listo. Ancla confirmada.")
                .font(.title2.bold())
            Button("Continuar") {
                dismiss()
            }
            .buttonStyle(.borderedProminent)
        }
    }
}

#Preview {
    QRAnchorSetupView()
        .modelContainer(for: QRAnchor.self, inMemory: true)
}
