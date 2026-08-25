import SwiftUI

struct AlarmRingingView: View {
    let alarm: AlarmConfig
    let anchor: QRAnchor?
    var onDismissed: () -> Void

    @State private var isScanning = false
    @State private var scanError = false
    @State private var backupCodeInput = ""
    @State private var showBackupCode = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let anchor {
                ringingContent(anchor: anchor)
            } else {
                // Solo alcanzable si el ancla referenciada por la alarma fue borrada
                // después de crearla — hoy no hay UI para borrar anclas, así que este
                // estado es teórico. Se deja sin botón de salida a propósito: no debe
                // convertirse en una puerta trasera para apagar la alarma sin QR.
                missingAnchorContent
            }
        }
    }

    private func ringingContent(anchor: QRAnchor) -> some View {
        ZStack {
            if isScanning {
                QRScannerView { payload in
                    handleScan(payload, anchor: anchor)
                }
                .ignoresSafeArea()
            }

            VStack(spacing: 24) {
                Text(timeString)
                    .font(.system(size: 72, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)

                Text("Escanea el código de \(anchor.label) para apagarla.")
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)

                if scanError {
                    Text("Ese código no es el correcto.")
                        .foregroundStyle(.red)
                }

                Spacer()

                if showBackupCode {
                    backupCodeField(anchor: anchor)
                } else {
                    Button {
                        isScanning = true
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                            .font(.system(size: 44))
                            .foregroundStyle(.black)
                            .frame(width: 120, height: 120)
                            .background(Color(red: 0.89, green: 0.64, blue: 0.35))
                            .clipShape(Circle())
                    }

                    Button("Usar código de respaldo") {
                        showBackupCode = true
                    }
                    .foregroundStyle(.white.opacity(0.6))
                }
            }
            .padding(32)
        }
    }

    private var missingAnchorContent: some View {
        VStack(spacing: 16) {
            Text(timeString)
                .font(.system(size: 72, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white)
            Text("El ancla de esta alarma ya no existe.")
                .foregroundStyle(.white)
        }
    }

    private var timeString: String {
        String(format: "%02d:%02d", alarm.hour, alarm.minute)
    }

    private func backupCodeField(anchor: QRAnchor) -> some View {
        VStack(spacing: 12) {
            TextField("000000", text: $backupCodeInput)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.system(.title, design: .monospaced))
                .foregroundStyle(.white)
            Button("Confirmar") {
                submitBackupCode(anchor: anchor)
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private func handleScan(_ payload: String, anchor: QRAnchor) {
        if anchor.matches(scannedPayload: payload) {
            dismissAlarm()
        } else {
            scanError = true
            isScanning = false
        }
    }

    private func submitBackupCode(anchor: QRAnchor) {
        guard anchor.matches(enteredBackupCode: backupCodeInput) else {
            scanError = true
            return
        }
        dismissAlarm()
    }

    private func dismissAlarm() {
        Task { @MainActor in
            try? await AlarmScheduler.stop(alarm)
            onDismissed()
        }
    }
}

#Preview {
    AlarmRingingView(
        alarm: AlarmConfig(hour: 6, minute: 30),
        anchor: QRAnchor(label: "Espejo del baño"),
        onDismissed: {}
    )
}
