import SwiftUI
import AVFoundation

/// Vista de cámara reutilizable que reporta el string decodificado de un QR.
/// Se usa en el flujo de confirmación de ancla (M1) y en la pantalla de alarma
/// sonando (M2).
struct QRScannerView: UIViewControllerRepresentable {
    var torchOn: Bool = false
    var onScan: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onScan = onScan
        controller.torchOn = torchOn
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {
        // El cierre se reasigna en cada actualización: captura estado (ancla, errores)
        // que cambia, y uno viejo evaluaría contra datos desfasados.
        uiViewController.onScan = onScan
        uiViewController.torchOn = torchOn
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    var torchOn = false {
        didSet { applyTorch() }
    }

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var lastPayload: String?
    private var lastReportUptime: TimeInterval = 0

    /// Un mismo código a la vista se reporta una vez cada tanto, no en cada fotograma.
    /// Un código *distinto* se reporta de inmediato. Antes se reportaba una sola vez y
    /// ya: tras leer cualquier QR ajeno (un póster) el escáner no volvía a responder.
    private let repeatInterval: TimeInterval = 2

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        lastPayload = nil
        if !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.startRunning()
            }
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        applyTorch()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // La linterna queda encendida aunque se cierre la cámara si no se apaga a mano.
        setTorch(false)
        if session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.stopRunning()
            }
        }
    }

    /// Sin cámara disponible (Simulator, hardware ausente) la vista se queda negra en
    /// vez de crashear — el llamador siempre ofrece el código de respaldo.
    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer
    }

    private func applyTorch() {
        setTorch(torchOn)
    }

    private func setTorch(_ on: Bool) {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        } catch {
            // Sin linterna se sigue escaneando con la luz disponible.
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let readable = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              readable.type == .qr,
              let payload = readable.stringValue else {
            return
        }

        let now = ProcessInfo.processInfo.systemUptime
        if payload == lastPayload, now - lastReportUptime < repeatInterval {
            return
        }
        lastPayload = payload
        lastReportUptime = now
        onScan?(payload)
    }
}
