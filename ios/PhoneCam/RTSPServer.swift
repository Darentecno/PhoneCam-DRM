import Foundation
import Network

final class RTSPServer {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "com.phonecam.rtsp")
    private var connection: NWConnection?
    private var isPlaying = false
    private var bonjour: NetService?
    private var sps: Data?
    private var pps: Data?
    private var sequence: UInt16 = 1
    private let ssrc: UInt32 = UInt32.random(in: 1...UInt32.max)

    init(port: UInt16) throws {
        guard let listener = try? NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!) else {
            throw NSError(domain: "PhoneCam", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "No se pudo abrir el puerto RTSP."])
        }
        self.listener = listener
    }

    func start() {
        listener.stateUpdateHandler = { state in
            if case .failed(let error) = state { print("RTSP: \(error)") }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.connection?.cancel()
            self?.connection = connection
            connection.start(queue: self?.queue ?? .main)
            self?.receive()
        }
        listener.start(queue: queue)
        bonjour = NetService(domain: "local.", type: "_phonecam._tcp.", name: "PhoneCam",
                             port: 8554)
        bonjour?.publish()
    }

    func stop() {
        connection?.cancel()
        isPlaying = false
        bonjour?.stop()
        bonjour = nil
        listener.cancel()
    }

    func setConfig(sps: Data, pps: Data) {
        self.sps = sps
        self.pps = pps
    }

    func publish(nal: Data, pts: Int64, keyFrame: Bool) {
        guard isPlaying, let connection else { return }
        if keyFrame, let sps, let pps {
            sendRTP(sps, timestamp: UInt32(truncatingIfNeeded: pts), marker: false, connection: connection)
            sendRTP(pps, timestamp: UInt32(truncatingIfNeeded: pts), marker: false, connection: connection)
        }
        sendRTP(nal, timestamp: UInt32(truncatingIfNeeded: pts), marker: true, connection: connection)
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 8192) {
            [weak self] data, _, _, error in
            guard let self, error == nil else { return }
            if let data, let request = String(data: data, encoding: .isoLatin1) {
                self.handle(request)
            }
            self.receive()
        }
    }

    private func handle(_ request: String) {
        let lines = request.components(separatedBy: "\r\n")
        guard let line = lines.first else { return }
        let parts = line.split(separator: " ")
        guard let method = parts.first else { return }
        let cseq = lines.first(where: { $0.lowercased().hasPrefix("cseq:") })?
            .split(separator: ":", maxSplits: 1).last?
            .trimmingCharacters(in: .whitespaces) ?? "1"
        switch method {
        case "OPTIONS":
            respond(cseq: cseq, status: "200 OK", headers: "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
        case "DESCRIBE":
            guard let sps, let pps else {
                respond(cseq: cseq, status: "503 Service Unavailable", headers: "")
                return
            }
            let sdp = "v=0\r\ns=PhoneCam\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=\(sps.base64EncodedString()),\(pps.base64EncodedString())\r\na=control:streamid=0\r\n"
            respond(cseq: cseq, status: "200 OK",
                    headers: "Content-Type: application/sdp\r\nContent-Length: \(sdp.utf8.count)\r\n",
                    body: Data(sdp.utf8))
        case "SETUP":
            respond(cseq: cseq, status: "200 OK",
                    headers: "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: 1\r\n")
        case "PLAY":
            isPlaying = true
            respond(cseq: cseq, status: "200 OK", headers: "Session: 1\r\n")
        case "TEARDOWN":
            isPlaying = false
            respond(cseq: cseq, status: "200 OK", headers: "Session: 1\r\n")
            connection?.cancel()
        default:
            respond(cseq: cseq, status: "501 Not Implemented", headers: "")
        }
    }

    private func respond(cseq: String, status: String, headers: String, body: Data? = nil) {
        let response = "RTSP/1.0 \(status)\r\nCSeq: \(cseq)\r\nServer: PhoneCam-iOS\r\n\(headers)\r\n"
        var data = Data(response.utf8)
        if let body { data.append(body) }
        connection?.send(content: data, completion: .contentProcessed { _ in })
    }

    private func sendRTP(_ nal: Data, timestamp: UInt32, marker: Bool, connection: NWConnection) {
        let max = 1388
        if nal.count <= max {
            sendPacket(payload: nal, timestamp: timestamp, marker: marker, connection: connection)
            return
        }
        let header = nal[0]
        var offset = 1
        while offset < nal.count {
            let count = min(max - 2, nal.count - offset)
            var payload = Data([header & 0xe0 | 28, header & 0x1f |
                                (offset == 1 ? 0x80 : 0) |
                                (offset + count == nal.count ? 0x40 : 0)])
            payload.append(contentsOf: nal[offset..<(offset + count)])
            sendPacket(payload: payload, timestamp: timestamp,
                       marker: marker && offset + count == nal.count, connection: connection)
            offset += count
        }
    }

    private func sendPacket(payload: Data, timestamp: UInt32, marker: Bool, connection: NWConnection) {
        var rtp = Data([0x80, UInt8((marker ? 0x80 : 0) | 96),
                        UInt8(sequence >> 8), UInt8(sequence & 0xff),
                        UInt8(timestamp >> 24), UInt8(timestamp >> 16),
                        UInt8(timestamp >> 8), UInt8(timestamp & 0xff),
                        UInt8(ssrc >> 24), UInt8(ssrc >> 16), UInt8(ssrc >> 8), UInt8(ssrc)])
        sequence &+= 1
        rtp.append(payload)
        var framed = Data([0x24, 0, UInt8(rtp.count >> 8), UInt8(rtp.count & 0xff)])
        framed.append(rtp)
        connection.send(content: framed, completion: .contentProcessed { _ in })
    }
}
