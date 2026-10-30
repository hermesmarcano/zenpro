import SwiftUI
import SwiftData

/// Alta de un ancla por foto: etiqueta -> 1–3 fotos de referencia -> foto de
/// verificación. La verificación es obligatoria: si el umbral de coincidencia no
/// funciona para ese lugar, el usuario lo descubre aquí y no a las 6 a. m.
struct PhotoAnchorSetupView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var label = ""
    @State private var anchor: PhotoAnchor?
    @State private var step: Step = .label
    @State private var isShowingCamera = false
    @State private var isAnalyzing = false
    @State private var lastOutcome: PhotoVerifier.Outcome?
    @State private var analysisFailed = false

    private enum Step {
        case label
        case references
        case verify
        case cameraDenied
        case done
    }

    var body: some View {
        VStack(spacing: 24) {
            switch step {
            case .label: labelStep
            case .references: referencesStep
            case .verify: verifyStep
            case .cameraDenied: cameraDeniedStep
            case .done: doneStep
            }
        }
        .padding(24)
        .animation(.default, value: step)
        .fullScreenCover(isPresented: $isShowingCamera) {
            PhotoCaptureView(
                prompt: step == .verify
                    ? "Toma la foto como lo harás al apagar la alarma."
                    : "Encuadra el lugar desde donde vas a apagar la alarma.",
                onCapture: handleCapture,
                onCancel: { isShowingCamera = false }
            )
        }
    }

    // MARK: - Paso 1: etiqueta

    private var labelStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("¿Qué lugar vas a fotografiar?")
                .font(.title2.bold())
            Text("Tendrás que volver ahí y tomar otra foto para apagar la alarma.")
                .foregroundStyle(.secondary)
            TextField("Ej. Patio", text: $label)
                .textFieldStyle(.roundedBorder)
            Button("Continuar") {
                startEnrollment()
            }
            .disabled(label.trimmingCharacters(in: .whitespaces).isEmpty)
            .buttonStyle(.borderedProminent)
        }
    }

    private func startEnrollment() {
        let created = AnchorStore(context: modelContext)
            .pendingOrNewPhotoAnchor(label: label.trimmingCharacters(in: .whitespaces))
        anchor = created
        Task { @MainActor in
            let granted = await CameraPermission.request()
            step = granted ? .references : .cameraDenied
        }
    }

    // MARK: - Paso 2: fotos de referencia

    private var referencesStep: some View {
        VStack(spacing: 16) {
            Text("Fotos de referencia")
                .font(.title2.bold())
            Text("Toma una desde donde vas a estar al apagar la alarma. Añade otra con poca luz, como a la hora en que sonará (recomendado).")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            if let anchor {
                HStack(spacing: 12) {
                    ForEach(Array(anchor.referenceImages.enumerated()), id: \.offset) { _, data in
                        if let image = UIImage(data: data) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 88, height: 88)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }

                if anchor.canAddReference {
                    Button(anchor.referenceImages.isEmpty ? "Tomar foto" : "Añadir otra foto") {
                        isShowingCamera = true
                    }
                    .buttonStyle(.bordered)
                }

                Button("Continuar") {
                    step = .verify
                }
                .buttonStyle(.borderedProminent)
                .disabled(anchor.referenceImages.isEmpty)
            }
        }
    }

    // MARK: - Paso 3: verificación

    private var verifyStep: some View {
        VStack(spacing: 16) {
            Text("Comprueba que funciona")
                .font(.title2.bold())
            Text("Aléjate un momento y vuelve a tomar una foto del lugar, como lo harás cuando suene la alarma.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            if isAnalyzing {
                ProgressView("Analizando…")
            } else {
                Button("Tomar foto de verificación") {
                    isShowingCamera = true
                }
                .buttonStyle(.borderedProminent)
            }

            if analysisFailed {
                Text("No se pudo analizar la foto. Inténtalo de nuevo.")
                    .foregroundStyle(.red)
                    .font(.footnote)
            }

            if let lastOutcome, !lastOutcome.isMatch {
                VStack(spacing: 8) {
                    Text("No coincide con tus referencias.")
                        .foregroundStyle(.red)
                    Text("Prueba desde el mismo punto, o añade otra referencia con la luz de esa hora.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    // Dato útil para calibrar el umbral con fotos reales.
                    Text(String(format: "distancia %.2f · umbral %.2f", lastOutcome.bestDistance, PhotoMatchPolicy.defaultThreshold))
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.secondary)
                    Button("Añadir otra referencia") {
                        self.lastOutcome = nil
                        step = .references
                    }
                    .buttonStyle(.bordered)
                    .disabled(!(anchor?.canAddReference ?? false))
                }
            }
        }
    }

    // MARK: - Captura

    private func handleCapture(_ image: UIImage) {
        isShowingCamera = false
        guard let anchor else { return }

        switch step {
        case .references:
            guard anchor.canAddReference,
                  let normalized = PhotoNormalizer.normalized(image),
                  let data = PhotoNormalizer.jpegData(from: normalized) else { return }
            anchor.referenceImages.append(data)

        case .verify:
            analysisFailed = false
            lastOutcome = nil
            isAnalyzing = true
            let references = anchor.referenceImages
            Task { @MainActor in
                let outcome = await PhotoVerifier.verify(candidate: image, references: references)
                isAnalyzing = false
                guard let outcome else {
                    analysisFailed = true
                    return
                }
                lastOutcome = outcome
                if outcome.isMatch {
                    AnchorStore(context: modelContext).confirm(anchor)
                    step = .done
                }
            }

        default:
            break
        }
    }

    // MARK: - Sin cámara

    private var cameraDeniedStep: some View {
        VStack(spacing: 16) {
            Text("No tenemos acceso a la cámara.")
                .font(.title3.bold())
            Text("El ancla por foto necesita la cámara. Actívala en Ajustes.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            if let url = URL(string: UIApplication.openSettingsURLString) {
                Link("Abrir Ajustes", destination: url)
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    // MARK: - Listo

    private var doneStep: some View {
        VStack(spacing: 16) {
            Text("Listo. Ancla confirmada.")
                .font(.title2.bold())
            if let anchor {
                Text("Código de respaldo: \(anchor.backupCode)")
                    .font(.system(.body, design: .monospaced))
                Text("Guárdalo: es la salida si la cámara falla o la foto no coincide.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Button("Continuar") {
                dismiss()
            }
            .buttonStyle(.borderedProminent)
        }
    }
}

#Preview {
    PhotoAnchorSetupView()
        .modelContainer(for: PhotoAnchor.self, inMemory: true)
}
