package com.phonecam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Servidor RTSP que implementa el handshake completo (OPTIONS, DESCRIBE, SETUP,
 * PLAY, TEARDOWN, GET_PARAMETER) y transmite el video codificado por
 * H264Encoder como RTP "interleaved" sobre la misma conexiÃ³n TCP (RFC 2326 Â§10.12).
 *
 * Soporta un cliente reproduciendo a la vez (pensado para un solo receptor,
 * ej. OBS/FFmpeg en el PC). Si otro cliente hace PLAY, reemplaza al anterior.
 */
public class RTSPService extends Service implements H264Encoder.Listener {
    private static final String TAG = "RTSPService";
    private static final int RTSP_PORT = 8554;
    private static final String NOTIF_CHANNEL_ID = "phonecam_stream";
    private static final int NOTIF_ID = 1;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private CameraService cameraService;
    private volatile boolean isRunning = false;
    private Handler mainHandler;

    // Ãšltimos SPS/PPS conocidos (Annex-B, sin start code), usados para el SDP
    // y para reenviarlos delante del primer keyframe de cada nueva sesiÃ³n.
    private volatile byte[] lastSps;
    private volatile byte[] lastPps;

    // SesiÃ³n activa (un solo cliente reproduciendo a la vez).
    private volatile ClientSession activeSession;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "RTSPService creado");
        mainHandler = new Handler(Looper.getMainLooper());
        executorService = Executors.newCachedThreadPool();

        startForegroundWithNotification();

        cameraService = new CameraService(this);
        startServer();
        cameraService.startCamera(this);

        showToast("ðŸ“¹ Servidor RTSP iniciado en puerto " + RTSP_PORT);
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "PhoneCam streaming", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("PhoneCam")
                .setContentText("Transmitiendo cÃ¡mara por RTSP")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    // ---- H264Encoder.Listener ----

    @Override
    public void onConfig(byte[] sps, byte[] pps) {
        lastSps = sps;
        lastPps = pps;
        Log.d(TAG, "SPS/PPS listos (" + sps.length + "/" + pps.length + " bytes)");
    }

    @Override
    public void onNalUnit(byte[] nal, boolean isKeyFrame, long ptsUs) {
        ClientSession session = activeSession;
        if (session != null && session.playing) {
            session.offer(new Frame(nal, isKeyFrame, ptsUs));
        }
    }

    @Override
    public void onEncoderError(Exception e) {
        Log.e(TAG, "Error del encoder", e);
        showToast("âŒ Error del encoder H.264");
    }

    // ---- Servidor RTSP ----

    private void startServer() {
        isRunning = true;
        executorService.execute(() -> {
            try {
                serverSocket = new ServerSocket(RTSP_PORT);
                Log.d(TAG, "RTSP Server iniciado en puerto " + RTSP_PORT);

                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Log.d(TAG, "Cliente conectado: " + clientSocket.getInetAddress().getHostAddress());
                        executorService.execute(new ClientSession(clientSocket));
                    } catch (IOException e) {
                        if (isRunning) Log.e(TAG, "Error aceptando conexiÃ³n", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error iniciando servidor RTSP", e);
                showToast("âŒ Error iniciando servidor RTSP (Â¿puerto ocupado?)");
            }
        });
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(RTSPService.this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        ClientSession session = activeSession;
        if (session != null) session.close();

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error cerrando server socket", e);
        }

        if (cameraService != null) cameraService.stopCamera();
        if (executorService != null) executorService.shutdownNow();

        showToast("â¹ï¸ Servidor RTSP detenido");
        Log.d(TAG, "RTSPService destruido");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class Frame {
        final byte[] nal;
        final boolean keyFrame;
        final long ptsUs;

        Frame(byte[] nal, boolean keyFrame, long ptsUs) {
            this.nal = nal;
            this.keyFrame = keyFrame;
            this.ptsUs = ptsUs;
        }
    }

    /** Maneja una conexiÃ³n de cliente: parsea RTSP y, si hace PLAY, transmite RTP. */
    private class ClientSession implements Runnable {
        private final Socket socket;
        private final String sessionId = Long.toString(Math.abs(new Random().nextLong()));
        private final Object writeLock = new Object();
        private final RtpPacketizer packetizer = new RtpPacketizer(writeLock);
        private final LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<>(60);

        volatile boolean playing = false;
        private volatile boolean sentConfigForCurrentKeyframe = false;
        private Thread writerThread;
        private OutputStream out;

        ClientSession(Socket socket) {
            this.socket = socket;
        }

        void offer(Frame f) {
            if (!queue.offer(f)) {
                queue.poll(); // descarta el mÃ¡s viejo si la cola estÃ¡ llena
                queue.offer(f);
            }
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                out = socket.getOutputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    handleRequest(line, reader);
                }
            } catch (IOException e) {
                Log.d(TAG, "Cliente desconectado: " + e.getMessage());
            } finally {
                close();
            }
        }

        private void handleRequest(String requestLine, BufferedReader reader) throws IOException {
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];

            int cseq = 1;
            String transportHeader = null;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.startsWith("CSeq:")) {
                    try {
                        cseq = Integer.parseInt(headerLine.substring(5).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (headerLine.startsWith("Transport:")) {
                    transportHeader = headerLine.substring(10).trim();
                }
            }

            Log.d(TAG, "RTSP " + method + " (CSeq " + cseq + ")");

            switch (method) {
                case "OPTIONS":
                    sendResponse(cseq, "200 OK",
                            "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n");
                    break;
                case "DESCRIBE":
                    handleDescribe(cseq);
                    break;
                case "SETUP":
                    handleSetup(cseq, transportHeader);
                    break;
                case "PLAY":
                    handlePlay(cseq);
                    break;
                case "GET_PARAMETER":
                    sendResponse(cseq, "200 OK", "Session: " + sessionId + "\r\n");
                    break;
                case "TEARDOWN":
                    sendResponse(cseq, "200 OK", "Session: " + sessionId + "\r\n");
                    playing = false;
                    close();
                    break;
                default:
                    sendResponse(cseq, "501 Not Implemented", "");
            }
        }

        private void handleDescribe(int cseq) throws IOException {
            // Espera brevemente a que el encoder produzca SPS/PPS si el cliente
            // pregunta antes de que la cÃ¡mara haya arrancado del todo.
            for (int i = 0; i < 40 && (lastSps == null || lastPps == null); i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
            if (lastSps == null || lastPps == null) {
                sendResponse(cseq, "503 Service Unavailable", "");
                return;
            }

            String spsB64 = Base64.encodeToString(lastSps, Base64.NO_WRAP);
            String ppsB64 = Base64.encodeToString(lastPps, Base64.NO_WRAP);
            String profileLevelId = String.format("%02X%02X%02X",
                    lastSps[1] & 0xFF, lastSps[2] & 0xFF, lastSps[3] & 0xFF);
            String ip = NetworkUtils.getLocalIpAddress();

            String sdp = "v=0\r\n" +
                    "o=- 0 0 IN IP4 " + ip + "\r\n" +
                    "s=PhoneCam\r\n" +
                    "c=IN IP4 " + ip + "\r\n" +
                    "t=0 0\r\n" +
                    "m=video 0 RTP/AVP 96\r\n" +
                    "a=rtpmap:96 H264/90000\r\n" +
                    "a=fmtp:96 packetization-mode=1;profile-level-id=" + profileLevelId +
                    ";sprop-parameter-sets=" + spsB64 + "," + ppsB64 + "\r\n" +
                    "a=control:streamid=0\r\n";

            byte[] sdpBytes = sdp.getBytes("UTF-8");
            String headers = "Content-Base: rtsp://" + ip + ":" + RTSP_PORT + "/\r\n" +
                    "Content-Type: application/sdp\r\n" +
                    "Content-Length: " + sdpBytes.length + "\r\n";
            sendResponse(cseq, "200 OK", headers, sdpBytes);
        }

        private void handleSetup(int cseq, String transportHeader) throws IOException {
            // Solo soportamos TCP interleaved (coincide con -rtsp_transport tcp de FFmpeg).
            String transport = "RTP/AVP/TCP;unicast;interleaved=0-1";
            sendResponse(cseq, "200 OK",
                    "Transport: " + transport + "\r\n" +
                            "Session: " + sessionId + ";timeout=60\r\n");
        }

        private void handlePlay(int cseq) throws IOException {
            sendResponse(cseq, "200 OK",
                    "Session: " + sessionId + "\r\n" +
                            "Range: npt=0.000-\r\n");

            playing = true;
            sentConfigForCurrentKeyframe = false;
            activeSession = this; // pasa a ser la sesiÃ³n activa (reemplaza a cualquier otra)

            writerThread = new Thread(this::writeLoop, "RTP-Writer");
            writerThread.start();
        }

        private void writeLoop() {
            while (playing) {
                Frame f;
                try {
                    f = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (f == null) continue;

                try {
                    // Antes del primer keyframe de la sesiÃ³n, reenvÃ­a SPS/PPS
                    // in-band (ademÃ¡s de ir en el SDP; ayuda a decoders estrictos).
                    if (f.keyFrame && !sentConfigForCurrentKeyframe && lastSps != null && lastPps != null) {
                        packetizer.sendNal(out, 0, lastSps, f.ptsUs, false);
                        packetizer.sendNal(out, 0, lastPps, f.ptsUs, false);
                        sentConfigForCurrentKeyframe = true;
                    }
                    packetizer.sendNal(out, 0, f.nal, f.ptsUs, true);
                } catch (IOException e) {
                    Log.d(TAG, "Cliente cerrÃ³ la conexiÃ³n durante el streaming");
                    playing = false;
                }
            }
        }

        private void sendResponse(int cseq, String status, String extraHeaders) throws IOException {
            sendResponse(cseq, status, extraHeaders, null);
        }

        private void sendResponse(int cseq, String status, String extraHeaders, byte[] body) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("RTSP/1.0 ").append(status).append("\r\n");
            sb.append("CSeq: ").append(cseq).append("\r\n");
            sb.append("Server: PhoneCam/1.0\r\n");
            sb.append(extraHeaders);
            sb.append("\r\n");

            synchronized (writeLock) {
                out.write(sb.toString().getBytes("UTF-8"));
                if (body != null) out.write(body);
                out.flush();
            }
        }

        void close() {
            playing = false;
            if (activeSession == this) activeSession = null;
            if (writerThread != null) writerThread.interrupt();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
