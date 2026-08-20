package com.phonecam;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RTSPService extends Service {
    private static final String TAG = "RTSPService";
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private CameraService cameraService;
    private boolean isRunning = false;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "RTSPService creado");
        executorService = Executors.newCachedThreadPool();
        cameraService = new CameraService(this);
        mainHandler = new Handler(Looper.getMainLooper());
        startServer();
        cameraService.startCamera();

        showToast("📹 Servidor RTSP iniciado en puerto 8554");
    }

    private void startServer() {
        isRunning = true;
        executorService.execute(() -> {
            try {
                serverSocket = new ServerSocket(8554);
                Log.d(TAG, "RTSP Server iniciado en puerto 8554");
                showToast("✅ RTSP Server listo en puerto 8554");

                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Log.d(TAG, "Cliente conectado: " + clientSocket.getInetAddress().getHostAddress());
                        executorService.execute(new RTSPHandler(clientSocket));
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error aceptando conexión", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error iniciando servidor RTSP", e);
                showToast("❌ Error iniciando servidor RTSP");
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
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error cerrando server socket", e);
        }

        if (cameraService != null) {
            cameraService.stopCamera();
        }

        if (executorService != null) {
            executorService.shutdown();
        }

        showToast("⏹️ Servidor RTSP detenido");
        Log.d(TAG, "RTSPService destruido");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class RTSPHandler implements Runnable {
        private final Socket clientSocket;

        RTSPHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                String response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: 1\r\n" +
                        "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n" +
                        "Server: PhoneCam/1.0\r\n" +
                        "\r\n";
                clientSocket.getOutputStream().write(response.getBytes());
                clientSocket.getOutputStream().flush();
                clientSocket.close();
                Log.d(TAG, "Respuesta RTSP enviada a cliente");
            } catch (IOException e) {
                Log.e(TAG, "Error manejando cliente RTSP", e);
            }
        }
    }
}