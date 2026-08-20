package com.phonecam;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;

/**
 * Empaqueta NAL units H.264 en paquetes RTP (RFC 6184) y los envÃ­a por el
 * mismo socket TCP de RTSP usando "interleaved framing" (RFC 2326 Â§10.12):
 * cada paquete va precedido de '$' + canal (1 byte) + longitud (2 bytes, big endian).
 *
 * Esto es lo que FFmpeg espera cuando se conecta con -rtsp_transport tcp,
 * como ya hace rtsp_client.py del lado del PC.
 */
public class RtpPacketizer {
    private static final int MTU = 1400; // tamaÃ±o seguro para redes WiFi locales
    private static final int CLOCK_RATE = 90000; // reloj estÃ¡ndar RTP para video
    private static final int PAYLOAD_TYPE = 96; // dinÃ¡mico, definido en el SDP

    private int seq;
    public final long ssrc;
    private final Object writeLock;

    public RtpPacketizer(Object writeLock) {
        Random r = new Random();
        this.seq = r.nextInt(65535);
        this.ssrc = r.nextInt();
        this.writeLock = writeLock;
    }

    /**
     * EnvÃ­a un NAL unit (sin start code) como uno o mÃ¡s paquetes RTP.
     * @param marker true si este NAL es el Ãºltimo de la unidad de acceso (el frame de video),
     *               false para NALs no-VCL como SPS/PPS que preceden al frame.
     */
    public void sendNal(OutputStream out, int channel, byte[] nal, long ptsUs, boolean marker) throws IOException {
        long timestamp = (ptsUs * CLOCK_RATE) / 1_000_000L;
        int maxPayload = MTU - 12; // resta el header RTP de 12 bytes

        if (nal.length <= maxPayload) {
            byte[] packet = new byte[12 + nal.length];
            writeRtpHeader(packet, timestamp, marker);
            System.arraycopy(nal, 0, packet, 12, nal.length);
            writeInterleaved(out, channel, packet);
        } else {
            // FragmentaciÃ³n FU-A (RFC 6184 Â§5.8)
            int nalHeader = nal[0] & 0xFF;
            int fnri = nalHeader & 0xE0;
            int nalType = nalHeader & 0x1F;
            int fragMax = maxPayload - 2; // menos FU indicator + FU header
            int offset = 1; // el header original del NAL no se reenvÃ­a, va en el FU header
            int remaining = nal.length - 1;
            boolean first = true;

            while (remaining > 0) {
                int fragSize = Math.min(fragMax, remaining);
                boolean last = (remaining - fragSize) == 0;

                byte[] packet = new byte[12 + 2 + fragSize];
                writeRtpHeader(packet, timestamp, last && marker);
                packet[12] = (byte) (fnri | 28); // FU indicator, tipo 28 = FU-A
                int fuHeader = nalType;
                if (first) fuHeader |= 0x80; // start bit
                if (last) fuHeader |= 0x40;  // end bit
                packet[13] = (byte) fuHeader;
                System.arraycopy(nal, offset, packet, 14, fragSize);

                writeInterleaved(out, channel, packet);

                offset += fragSize;
                remaining -= fragSize;
                first = false;
            }
        }
    }

    private void writeRtpHeader(byte[] packet, long timestamp, boolean marker) {
        packet[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0
        packet[1] = (byte) ((marker ? 0x80 : 0x00) | PAYLOAD_TYPE);
        packet[2] = (byte) (seq >> 8);
        packet[3] = (byte) seq;
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;
        packet[8] = (byte) (ssrc >> 24);
        packet[9] = (byte) (ssrc >> 16);
        packet[10] = (byte) (ssrc >> 8);
        packet[11] = (byte) ssrc;
        seq = (seq + 1) & 0xFFFF;
    }

    private void writeInterleaved(OutputStream out, int channel, byte[] rtpPacket) throws IOException {
        synchronized (writeLock) {
            out.write('$');
            out.write(channel);
            out.write((rtpPacket.length >> 8) & 0xFF);
            out.write(rtpPacket.length & 0xFF);
            out.write(rtpPacket);
            out.flush();
        }
    }
}
