import SwiftUI

struct AlarmRingingView: View {
    let alarm: AlarmConfig
    let anchor: AnchorRef?
    /// `true` mientras el usuario está interactuando para apagar (cámara abierta,
    /// analizando o escribiendo el código). El volumen inteligente lo trata como
    /// "teléfono en mano" aunque esté quieto.
    var onEngagementChange: (Bool) -> Void = { _ in }
    var onDismissed: () -> Void

    @State private var isScanningQR = false
    @State private var isShowingPhotoCamera = false
    @State private var isVerifyingPhoto = false
    @State private var photoFailures = 0
    @State private var photoAnalysisFailed = false
    @State private var errorMessage: String?
    @State private var cameraDenied = false
    @State private var showBackupCode = false
    @State private var backupCodeInput = ""

    private let accent = Color(red: 0.89, green: 0.64, blue: 0.35)

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let anchor {
                ringingContent(anchor: anchor)
            } else {
                // Solo alcanzable si el ancla de la alarma fue borrada o nunca se
                // confirmó — hoy no hay UI para borrar anclas, así que es teórico. Se
                // deja sin botón de salida a propósito: no debe ser una puerta trasera
                // para apagar la alarma sin QR ni foto (ver ARCHITECTURE.md §5).
                missingAnchorContent
            }
        }
        .onChange(of: isEngaged) { _, engaged in
            onEngagementChange(engaged)
        }
        .fullScreenCover(isPresented: $isShowingPhotoCamera) {
            if case .photo(let photoAnchor) = anchor {
                PhotoCaptureView(
                    prompt: "Toma una foto de \(photoAnchor.label).",
                    onCapture: { image in handlePhoto(image, anchor: photoAnchor) },
                    onCancel: { isShowingPhotoCamera = false }
                )
            }
        }
    }

    private var isEngaged: Bool {
        isScanningQR || isShowingPhotoCamera || isVerifyingPhoto || showBackupCode
    }

    // MARK: - Contenido

    private func ringingContent(anchor: AnchorRef) -> some View {
        ZStack {
            if isScanningQR, case .qr(let qrAnchor) = anchor {
                QRScannerView { payload in
                    handleScan(payload, anchor: qrAnchor)
                }
                .ignoresSafeArea()
            }

            VStack(spacing: 24) {
                Text(timeString)
                    .font(.system(size: 72, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)

                Text(instruction(for: anchor))
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)

                feedback

                Spacer()

                if showBackupCode {
                    backupCodeField(anchor: anchor)
                } else if isVerifyingPhoto {
                    ProgressView("Analizando…")
                        .tint(.white)
                        .foregroundStyle(.white)
                } else {
                    Button {
                        beginDismissal(anchor: anchor)
                    } label: {
                        Image(systemName: icon(for: anchor))
                            .font(.system(size: 44))
                            .foregroundStyle(.black)
                            .frame(width: 120, height: 120)
                            .background(accent)
                            .clipShape(Circle())
                    }
                    .accessibilityLabel(accessibilityLabel(for: anchor))

                    Button("Usar código de respaldo") {
                        showBackupCode = true
                    }
                    .foregroundStyle(.white.opacity(0.6))
                }
            }
            .padding(32)
        }
    }

    @ViewBuilder
    private var feedback: some View {
        if cameraDenied {
            Text("Sin acceso a la cámara. Usa el código de respaldo.")
                .foregroundStyle(.red)
        } else if photoAnalysisFailed {
            Text("No se pudo analizar la foto. Inténtalo de nuevo.")
                .foregroundStyle(.red)
        } else if let errorMessage {
            VStack(spacing: 6) {
                Text(errorMessage)
                    .foregroundStyle(.red)
                if PhotoMatchPolicy.shouldShowHint(consecutiveFailures: photoFailures) {
                    Text("Prueba con más luz o usa el código de respaldo.")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
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

    private func instruction(for anchor: AnchorRef) -> String {
        switch anchor {
        case .qr(let qrAnchor): "Escanea el código de \(qrAnchor.label) para apagarla."
        case .photo(let photoAnchor): "Toma una foto de \(photoAnchor.label) para apagarla."
        }
    }

    private func icon(for anchor: AnchorRef) -> String {
        switch anchor {
        case .qr: "qrcode.viewfinder"
        case .photo: "camera"
        }
    }

    private func accessibilityLabel(for anchor: AnchorRef) -> String {
        switch anchor {
        case .qr: "Escanear código"
        case .photo: "Tomar foto"
        }
    }

    private func backupCodeField(anchor: AnchorRef) -> some View {
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

    // MARK: - Acciones

    /// Abre la cámara que corresponda al tipo de ancla, previa comprobación del permiso.
    /// Si la cámara no está disponible se pasa directo al código de respaldo: nunca se
    /// deja al usuario ante una pantalla negra sin salida.
    private func beginDismissal(anchor: AnchorRef) {
        errorMessage = nil
        photoAnalysisFailed = false
        Task { @MainActor in
            guard await CameraPermission.request() else {
                cameraDenied = true
                showBackupCode = true
                return
            }
            switch anchor {
            case .qr: isScanningQR = true
            case .photo: isShowingPhotoCamera = true
            }
        }
    }

    private func handleScan(_ payload: String, anchor: QRAnchor) {
        if anchor.matches(scannedPayload: payload) {
            dismissAlarm()
        } else {
            errorMessage = "Ese código no es el correcto."
            isScanningQR = false
        }
    }

    private func handlePhoto(_ image: UIImage, anchor: PhotoAnchor) {
        isShowingPhotoCamera = false
        isVerifyingPhoto = true
        let references = anchor.referenceImages
        Task { @MainActor in
            let outcome = await PhotoVerifier.verify(candidate: image, references: references)
            isVerifyingPhoto = false
            guard let outcome else {
                // Fallo técnico: no cuenta como intento fallido.
                photoAnalysisFailed = true
                return
            }
            if outcome.isMatch {
                dismissAlarm()
            } else {
                photoFailures += 1
                errorMessage = "No es el lugar correcto."
            }
        }
    }

    private func submitBackupCode(anchor: AnchorRef) {
        guard anchor.matches(enteredBackupCode: backupCodeInput) else {
            errorMessage = "Código de respaldo incorrecto."
            return
        }
        dismissAlarm()
    }

    /// La vista solo demuestra presencia; silenciar la alarma (sesión inteligente o
    /// alarma del sistema) lo decide `AlarmCoordinator` según cómo esté sonando.
    private func dismissAlarm() {
        onDismissed()
    }
}

#Preview {
    AlarmRingingView(
        alarm: AlarmConfig(hour: 6, minute: 30),
        anchor: .qr(QRAnchor(label: "Espejo del baño")),
        onDismissed: {}
    )
}
