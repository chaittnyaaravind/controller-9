import AVFoundation
import SwiftUI

/// Camera QR scanner (AVFoundation). Calls `onCode` once with the first ninectl:// code.
struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    func makeUIViewController(context: Context) -> ScannerController { let c = ScannerController(); c.onCode = onCode; return c }
    func updateUIViewController(_ vc: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var done = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
            session.addInput(input)
            let out = AVCaptureMetadataOutput()
            guard session.canAddOutput(out) else { return }
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = view.bounds
            view.layer.addSublayer(layer)
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
        }
        override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); view.layer.sublayers?.first?.frame = view.bounds }
        override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); session.stopRunning() }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let s = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue, s.hasPrefix("ninectl://pair?") else { return }
            done = true
            session.stopRunning()
            onCode?(s)
        }
    }
}
