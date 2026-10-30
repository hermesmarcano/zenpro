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
    @State private var torchOn = false
    @State private var isShowingPhotoCamera = false
    @State private var isVerifyingPhoto = false
    @State private var photoFailures = 0
    @State private var photoAnalysisFailed = false
    @State private var errorMessage: String?
    @State private var cameraDenied = false
    @State private var showBackupCode = false
    @State private var backupCodeInput = ""
    @FocusState private var backupCodeFocused: Bool
    /// Cuándo apareció esta pantalla (reloj monotónico), para la espera del código de
    /// respaldo. Si la app se reinicia la espera empieza de nuevo: más fricción, no menos.
    @State private var shownAt = ProcessInfo.processInfo.systemUptime

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
        .onChange(of: backupCodeInput) { _, newValue in
            let cleaned = BackupCodePolicy.sanitize(newValue)
            if cleaned != newValue {
                backupCodeInput = cleaned
            }
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

    /// Con scroll para que quepa en pantallas pequeñas (iPhone SE: 667 pt de alto) y con
    /// texto grande de accesibilidad; el `minHeight` mantiene el botón principal abajo
    /// cuando sobra espacio.
    private func ringingContent(anchor: AnchorRef) -> some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 24) {
                    Text(timeString)
                        .font(.system(size: 72, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.white)
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)

                    Text(instruction(for: anchor))
                        .foregroundStyle(.white.opacity(0.7))
                        .multilineTextAlignment(.center)

                    feedback

                    if isScanningQR, case .qr(let qrAnchor) = anchor {
                        QRScannerView(torchOn: torchOn) { payload in
                            handleScan(payload, anchor: qrAnchor)
                        }
                        .aspectRatio(1, contentMode: .fit)
                        .frame(maxWidth: 360)
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                    }

                    Spacer(minLength: 16)

                    controls(anchor: anchor)
                }
                .padding(32)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    @ViewBuilder
    private func controls(anchor: AnchorRef) -> some View {
        if showBackupCode {
            backupCodeField(anchor: anchor)
        } else if isVerifyingPhoto {
            ProgressView("Analizando…")
                .tint(.white)
                .foregroundStyle(.white)
        } else if isScanningQR {
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
                .tint(.white)

                Button("Cancelar") {
                    isScanningQR = false
                    torchOn = false
                }
                .buttonStyle(.bordered)
                .tint(.white)
            }
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

            backupCodeEntry
        }
    }

    /// El código de respaldo no está disponible de inmediato: sin espera se podría teclear
    /// desde la cama (el código se memoriza) y la alarma no exigiría ir a ningún sitio.
    private var backupCodeEntry: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let remaining = BackupCodePolicy.remaining(
                since: shownAt,
                now: ProcessInfo.processInfo.systemUptime
            )
            if remaining > 0 {
                Text("Código de respaldo disponible en \(BackupCodePolicy.countdownText(remaining))")
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.5))
                    .multilineTextAlignment(.center)
            } else {
                Button("Usar código de respaldo") {
                    showBackupCode = true
                    backupCodeFocused = true
                }
                .foregroundStyle(.white.opacity(0.6))
            }
        }
    }

    @ViewBuilder
    private var feedback: some View {
        if cameraDenied {
            VStack(spacing: 6) {
                Text("Sin acceso a la cámara.")
                    .foregroundStyle(.red)
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    Link("Abrir Ajustes", destination: url)
                        .font(.footnote)
                }
            }
        } else if photoAnalysisFailed {
            Text("No se pudo analizar la foto. Inténtalo de nuevo.")
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        } else if let errorMessage {
            VStack(spacing: 6) {
                Text(errorMessage)
                    .foregroundStyle(.red)
                if PhotoMatchPolicy.shouldShowHint(consecutiveFailures: photoFailures) {
                    Text("Prueba con más luz o usa el código de respaldo.")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
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
                .focused($backupCodeFocused)
                .multilineTextAlignment(.center)
                .font(.system(.title, design: .monospaced))
                .foregroundStyle(.white)
                .tint(.white)
            HStack(spacing: 16) {
                Button("Volver") {
                    showBackupCode = false
                    backupCodeInput = ""
                    errorMessage = nil
                }
                .buttonStyle(.bordered)
                .tint(.white)

                Button("Confirmar") {
                    submitBackupCode(anchor: anchor)
                }
                .buttonStyle(.borderedProminent)
                .disabled(backupCodeInput.count != BackupCodePolicy.length)
            }
        }
    }

    // MARK: - Acciones

    /// Abre la cámara que corresponda al tipo de ancla, previa comprobación del permiso.
    /// Si no hay permiso se avisa con un acceso a Ajustes; el código de respaldo sigue
    /// sujeto a su espera (si se abriera al instante, negar la cámara sería un atajo).
    private func beginDismissal(anchor: AnchorRef) {
        errorMessage = nil
        photoAnalysisFailed = false
        cameraDenied = false
        Task { @MainActor in
            guard await CameraPermission.request() else {
                cameraDenied = true
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
            // El escáner sigue activo (un QR distinto se reporta de inmediato): basta
            // con avisar; no hace falta cerrar la cámara.
            errorMessage = "Ese no es el código de \(anchor.label)."
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
