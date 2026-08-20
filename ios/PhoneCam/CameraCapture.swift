import AVFoundation
import VideoToolbox

final class CameraCapture: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.phonecam.capture")
    private var encoder: H264Encoder?
    private var onNAL: ((Data, Int64, Bool) -> Void)?

    func start(onConfig: @escaping (Data, Data) -> Void,
               onNAL: @escaping (Data, Int64, Bool) -> Void) throws {
        self.onNAL = onNAL
        session.beginConfiguration()
        session.sessionPreset = .hd1280x720
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                     for: .video, position: .back) else {
            throw NSError(domain: "PhoneCam", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No se encontró la cámara trasera."])
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw CaptureError.configuration }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String:
                                kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else { throw CaptureError.configuration }
        session.addOutput(output)
        session.commitConfiguration()
        encoder = try H264Encoder(width: 1280, height: 720, onConfig: onConfig) { [weak self] data, pts, key in
            self?.onNAL?(data, pts, key)
        }
        session.startRunning()
    }

    func stop() {
        session.stopRunning()
        encoder?.stop()
        encoder = nil
        onNAL = nil
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        encoder?.encode(imageBuffer: imageBuffer,
                        presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
    }
}

private enum CaptureError: Error { case configuration }
