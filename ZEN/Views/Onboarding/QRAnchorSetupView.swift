import SwiftUI
import SwiftData

struct QRAnchorSetupView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var label = ""
    @State private var anchor: QRAnchor?
    @State private var qrImage: UIImage?
    @State private var printableImage: UIImage?
    @State private var step: Step = .label
    @State private var torchOn = false
    @State private var scanError: String?
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
        ScrollView {
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
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
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
        let qr = QRCodeGenerator.image(for: created.qrPayload)
        qrImage = qr
        printableImage = qr.flatMap {
            PrintableAnchor.image(label: created.label, backupCode: created.backupCode, qr: $0)
        }
        step = .showQR
    }

    // MARK: - Paso 2: mostrar QR para imprimir/pegar

    private var qrStep: some View {
        VStack(spacing: 20) {
            Text("Imprime este código y pégalo en un punto fijo, fuera de tu cuarto.")
                .font(.title3.bold())
                .multilineTextAlignment(.center)

            if let qrImage {
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 240, maxHeight: 240)
                    .accessibilityLabel("Código QR de \(anchor?.label ?? "tu ancla")")
            }

            if let anchor {
                VStack(spacing: 4) {
                    Text("Código de respaldo: \(anchor.backupCode)")
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundStyle(.secondary)
                    Text("Ya va impreso en la hoja, junto al QR. Es la salida si falla la cámara, y no se habilita hasta pasados unos minutos de alarma.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }

            if let printableImage, let anchor {
                ShareLink(
                    item: Image(uiImage: printableImage),
                    preview: SharePreview("Código de \(anchor.label)", image: Image(uiImage: printableImage))
                ) {
                    Label("Compartir para imprimir", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.bordered)
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
        scanError = nil
        Task { @MainActor in
            let granted = await CameraPermission.request()
            step = granted ? .scanning : .cameraDenied
        }
    }

    // MARK: - Paso 3: escaneo real

    private var scanningStep: some View {
        VStack(spacing: 16) {
            Text("Apunta al código pegado en \(anchor?.label ?? "el lugar").")
                .font(.headline)
                .multilineTextAlignment(.center)

            QRScannerView(torchOn: torchOn) { payload in
                handleScan(payload)
            }
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: 360)
            .clipShape(RoundedRectangle(cornerRadius: 20))

            if let scanError {
                Text(scanError)
                    .foregroundStyle(.red)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: 16) {
                Button {
                    torchOn.toggle()
                } label: {
                    Label(
                        torchOn ? "Apagar linterna" : "Linterna",
                        systemImage: torchOn ? "flashlight.on.fill" : "flashlight.off.fill"
                    )
                }
                .buttonStyle(.bordered)

                Button("Usar el código de respaldo") {
                    torchOn = false
                    step = .backupCode
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private func handleScan(_ payload: String) {
        guard let anchor else { return }
        if anchor.matches(scannedPayload: payload) {
            torchOn = false
            confirm(anchor)
        } else {
            // El escáner sigue activo: sin este aviso, leer un QR cualquiera (un póster)
            // parecía no hacer nada.
            scanError = "Ese no es el código de ZEN que generaste. Apunta al que imprimiste."
        }
    }

    // MARK: - Cámara denegada

    private var cameraDeniedStep: some View {
        VStack(spacing: 16) {
            Text("No tenemos acceso a la cámara.")
                .font(.title3.bold())
            Text("Actívala en Ajustes, o confirma por ahora con el código de respaldo.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            if let url = URL(string: UIApplication.openSettingsURLString) {
                Link("Abrir Ajustes", destination: url)
                    .buttonStyle(.bordered)
            }
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
                .onChange(of: backupCodeInput) { _, newValue in
                    let cleaned = BackupCodePolicy.sanitize(newValue)
                    if cleaned != newValue {
                        backupCodeInput = cleaned
                    }
                    backupCodeError = false
                }
            if backupCodeError {
                Text("Ese código no coincide.")
                    .foregroundStyle(.red)
                    .font(.footnote)
            }
            Button("Confirmar") {
                submitBackupCode()
            }
            .buttonStyle(.borderedProminent)
            .disabled(backupCodeInput.count != BackupCodePolicy.length)
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
