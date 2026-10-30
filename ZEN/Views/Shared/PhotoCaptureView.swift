import SwiftUI
import AVFoundation

/// Cámara de fotos fijas en vivo. No hay acceso a la fototeca a propósito: la foto que
/// apaga una alarma tiene que tomarse en el momento, no subirse desde el carrete.
final class PhotoCameraModel: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    @Published private(set) var isReady = false
    @Published private(set) var isCapturing = false

    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "zen.photo-camera")
    private var isConfigured = false
    private var onPhoto: ((UIImage) -> Void)?

    func start(onPhoto: @escaping (UIImage) -> Void) {
        self.onPhoto = onPhoto
        queue.async { [weak self] in
            guard let self else { return }
            if !self.isConfigured {
                self.isConfigured = self.configureSession()
            }
            // Sin cámara (Simulator, hardware ausente) se queda no-lista: la pantalla
            // lo muestra y el código de respaldo sigue disponible.
            guard self.isConfigured else { return }
            if !self.session.isRunning {
                self.session.startRunning()
            }
            DispatchQueue.main.async { self.isReady = true }
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
        isReady = false
    }

    func capture() {
        guard isReady, !isCapturing else { return }
        isCapturing = true
        queue.async { [weak self] in
            guard let self else { return }
            self.output.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
        }
    }

    private func configureSession() -> Bool {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input),
              session.canAddOutput(output) else {
            return false
        }
        session.addInput(input)
        session.addOutput(output)
        return true
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        let image = error == nil ? photo.fileDataRepresentation().flatMap { UIImage(data: $0) } : nil
        DispatchQueue.main.async {
            self.isCapturing = false
            if let image {
                self.onPhoto?(image)
            }
        }
    }
}

private final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        // `layerClass` garantiza el tipo de la capa.
        layer as! AVCaptureVideoPreviewLayer
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> UIView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

struct PhotoCaptureView: View {
    let prompt: String
    var onCapture: (UIImage) -> Void
    var onCancel: (() -> Void)?

    @StateObject private var camera = PhotoCameraModel()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if camera.isReady {
                CameraPreview(session: camera.session)
                    .ignoresSafeArea()
            }

            VStack(spacing: 16) {
                HStack {
                    if let onCancel {
                        Button("Cancelar", action: onCancel)
                            .foregroundStyle(.white)
                    }
                    Spacer()
                }

                Text(prompt)
                    .font(.callout)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(10)
                    .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))

                Spacer()

                if !camera.isReady {
                    Text("Cámara no disponible")
                        .foregroundStyle(.white.opacity(0.7))
                }

                Button {
                    camera.capture()
                } label: {
                    Circle()
                        .strokeBorder(.white, lineWidth: 4)
                        .background(Circle().fill(.white.opacity(camera.isCapturing ? 0.3 : 0.9)).padding(6))
                        .frame(width: 72, height: 72)
                }
                .disabled(!camera.isReady || camera.isCapturing)
                .accessibilityLabel("Tomar foto")
            }
            .padding(24)
        }
        .onAppear { camera.start(onPhoto: onCapture) }
        .onDisappear { camera.stop() }
    }
}
