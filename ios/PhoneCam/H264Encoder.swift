import Foundation
import VideoToolbox

final class H264Encoder {
    private var session: VTCompressionSession?
    private let onConfig: (Data, Data) -> Void
    private let onNAL: (Data, Int64, Bool) -> Void

    init(width: Int, height: Int, onConfig: @escaping (Data, Data) -> Void,
         onNAL: @escaping (Data, Int64, Bool) -> Void) throws {
        self.onConfig = onConfig
        self.onNAL = onNAL
        var created: VTCompressionSession?
        let status = VTCompressionSessionCreate(allocator: nil, width: Int32(width),
            height: Int32(height), codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: H264Encoder.outputCallback, refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &created)
        guard status == noErr, let created else { throw NSError(domain: "PhoneCam", code: Int(status)) }
        session = created
        VTSessionSetProperty(created, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        VTSessionSetProperty(created, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        VTSessionSetProperty(created, kVTCompressionPropertyKey_MaxKeyFrameInterval,
                             30 as CFTypeRef)
        VTCompressionSessionPrepareToEncodeFrames(created)
    }

    func encode(imageBuffer: CVImageBuffer, presentationTimeStamp: CMTime) {
        guard let session else { return }
        let pts = CMTimeGetSeconds(presentationTimeStamp)
        VTCompressionSessionEncodeFrame(session, imageBuffer: imageBuffer,
            presentationTimeStamp: presentationTimeStamp, duration: .invalid,
            frameProperties: nil, sourceFrameRefcon: nil, infoFlagsOut: nil)
        _ = pts
    }

    func stop() {
        if let session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
        }
        session = nil
    }

    private static let outputCallback: VTCompressionOutputCallback = {
        refcon, _, status, _, sampleBuffer in
        guard status == noErr, let refcon, let sampleBuffer,
              CMSampleBufferDataIsReady(sampleBuffer) else { return }
        let encoder = Unmanaged<H264Encoder>.fromOpaque(refcon).takeUnretainedValue()
        guard let format = CMSampleBufferGetFormatDescription(sampleBuffer),
              let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        var isKey = true
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: NSDictionary.self)
            isKey = !(dict[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
        }
        encoder.emit(sampleBuffer: sampleBuffer, format: format, buffer: dataBuffer, key: isKey)
    }

    private func emit(sampleBuffer: CMSampleBuffer, format: CMFormatDescription,
                      buffer: CMBlockBuffer, key: Bool) {
        var length = 0, total = 0
        var pointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(buffer, atOffset: 0, lengthAtOffsetOut: &length,
                                          totalLengthOut: &total, dataPointerOut: &pointer) == kCMBlockBufferNoErr,
              let pointer else { return }
        var offset = 0
        while offset + 4 <= total {
            let size = Int(UInt32(bigEndian: pointer.advanced(by: offset).withMemoryRebound(to: UInt32.self, capacity: 1) { $0.pointee }))
            offset += 4
            guard size > 0, offset + size <= total else { break }
            onNAL(Data(bytes: pointer.advanced(by: offset), count: size),
                  Int64(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer)) * 90_000), key)
            offset += size
        }
        if key {
            var sps: UnsafePointer<UInt8>?, pps: UnsafePointer<UInt8>?
            var spsSize = 0, ppsSize = 0, spsCount = 0, ppsCount = 0
            let spsStatus = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: 0, parameterSetPointerOut: &sps,
                parameterSetSizeOut: &spsSize, parameterSetCountOut: &spsCount,
                nalUnitHeaderLengthOut: nil)
            let ppsStatus = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: 1, parameterSetPointerOut: &pps,
                parameterSetSizeOut: &ppsSize, parameterSetCountOut: &ppsCount,
                nalUnitHeaderLengthOut: nil)
            if spsStatus == noErr, ppsStatus == noErr, let sps, let pps {
                onConfig(Data(bytes: sps, count: spsSize), Data(bytes: pps, count: ppsSize))
            }
        }
    }
}
