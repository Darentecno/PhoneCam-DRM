import Foundation
import AVFoundation
import Network

final class StreamModel: NSObject, ObservableObject {
    @Published private(set) var isStreaming = false
    @Published private(set) var errorMessage: String?
    let ipAddress = LocalAddress.current
    private let capture = CameraCapture()
    private var server: RTSPServer?

    func start() {
        errorMessage = nil
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            DispatchQueue.main.async {
                guard granted else {
                    self?.errorMessage = "Permiso de cámara denegado."
                    return
                }
                do {
                    guard let self else { return }
                    let server = try RTSPServer(port: 8554)
                    self.server = server
                    try self.capture.start(onConfig: { [weak server] sps, pps in
                        server?.setConfig(sps: sps, pps: pps)
                    }) { [weak server] nal, pts, key in
                        server?.publish(nal: nal, pts: pts, keyFrame: key)
                    }
                    server.start()
                    self.isStreaming = true
                } catch {
                    self?.errorMessage = error.localizedDescription
                    self?.stop()
                }
            }
        }
    }

    func stop() {
        capture.stop()
        server?.stop()
        server = nil
        isStreaming = false
    }
}

enum LocalAddress {
    static var current: String {
        var address: String = "0.0.0.0"
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return address }
        defer { freeifaddrs(list) }
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = pointer.pointee
            guard String(cString: interface.ifa_name) == "en0",
                  interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                        &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            address = String(cString: host)
            break
        }
        return address
    }
}
