package com.phonecam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Codifica el video capturado por la cÃ¡mara a H.264 usando MediaCodec en modo
 * "Surface input": la cÃ¡mara escribe directo en la Surface del encoder, sin
 * pasar los frames YUV por la app (mÃ¡s eficiente y evita conversiones manuales).
 *
 * Entrega los NAL units (formato Annex-B, sin start code) al Listener a medida
 * que MediaCodec los va produciendo.
 */
public class H264Encoder {
    private static final String TAG = "H264Encoder";
    private static final String MIME = "video/avc";

    public interface Listener {
        /** SPS y PPS extraÃ­dos del primer buffer de configuraciÃ³n (BUFFER_FLAG_CODEC_CONFIG). */
        void onConfig(byte[] sps, byte[] pps);

        /** Un NAL unit codificado (VCL: frame de video), sin start code. */
        void onNalUnit(byte[] nal, boolean isKeyFrame, long ptsUs);

        void onEncoderError(Exception e);
    }

    private final int width;
    private final int height;
    private final int bitRate;
    private final Listener listener;

    private MediaCodec codec;
    private Surface inputSurface;
    private Thread drainThread;
    private volatile boolean running = false;

    public H264Encoder(int width, int height, int bitRate, Listener listener) {
        this.width = width;
        this.height = height;
        this.bitRate = bitRate;
        this.listener = listener;
    }

    public Surface start() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        // Keyframe cada 1 segundo: permite que un cliente que se conecta tarde
        // (o que sufre pÃ©rdida de paquetes) se resincronice rÃ¡pido.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }

        codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();

        running = true;
        drainThread = new Thread(this::drainLoop, "H264Encoder-Drain");
        drainThread.start();

        Log.d(TAG, "Encoder iniciado " + width + "x" + height + " @ " + bitRate + "bps");
        return inputSurface;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 10_000);
                if (idx >= 0) {
                    handleOutputBuffer(idx, info);
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // nada listo todavÃ­a, seguir esperando
                }
                // INFO_OUTPUT_FORMAT_CHANGED se ignora: ya obtenemos SPS/PPS
                // vÃ­a el buffer BUFFER_FLAG_CODEC_CONFIG mÃ¡s abajo.
            } catch (IllegalStateException e) {
                if (running) {
                    Log.e(TAG, "Error en drain loop", e);
                    if (listener != null) listener.onEncoderError(e);
                }
                break;
            }
        }
    }

    private void handleOutputBuffer(int idx, MediaCodec.BufferInfo info) {
        ByteBuffer buf = codec.getOutputBuffer(idx);
        try {
            if (buf != null && info.size > 0) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                buf.get(data);

                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                long ptsUs = info.presentationTimeUs;

                List<byte[]> nals = extractNalUnits(data);

                if (isConfig) {
                    byte[] sps = null, pps = null;
                    for (byte[] n : nals) {
                        int type = n[0] & 0x1F;
                        if (type == 7) sps = n;
                        else if (type == 8) pps = n;
                    }
                    if (sps != null && pps != null && listener != null) {
                        listener.onConfig(sps, pps);
                    }
                } else {
                    for (byte[] n : nals) {
                        if (listener != null) listener.onNalUnit(n, isKey, ptsUs);
                    }
                }
            }
        } finally {
            codec.releaseOutputBuffer(idx, false);
        }
    }

    /** Separa un buffer Annex-B (con start codes 00 00 01 / 00 00 00 01) en NAL units individuales. */
    private List<byte[]> extractNalUnits(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        int i = 0;
        while (i < data.length - 3) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i + 3);
                i += 3;
            } else if (i < data.length - 4 && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                starts.add(i + 4);
                i += 4;
            } else {
                i++;
            }
        }
        for (int s = 0; s < starts.size(); s++) {
            int start = starts.get(s);
            int end = (s + 1 < starts.size()) ? findStartCodeBegin(data, starts.get(s + 1)) : data.length;
            if (end > start) {
                byte[] nal = new byte[end - start];
                System.arraycopy(data, start, nal, 0, end - start);
                result.add(nal);
            }
        }
        return result;
    }

    private int findStartCodeBegin(byte[] data, int afterStartIdx) {
        // afterStartIdx apunta justo despuÃ©s de un start code de 3 o 4 bytes;
        // retrocedemos para no incluir el start code en el NAL anterior.
        if (afterStartIdx >= 4 && data[afterStartIdx - 4] == 0 && data[afterStartIdx - 3] == 0
                && data[afterStartIdx - 2] == 0 && data[afterStartIdx - 1] == 1) {
            return afterStartIdx - 4;
        }
        return afterStartIdx - 3;
    }

    public void stop() {
        running = false;
        if (drainThread != null) {
            try {
                drainThread.join(500);
            } catch (InterruptedException ignored) {
            }
            drainThread = null;
        }
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error deteniendo encoder", e);
            }
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
    }
}
