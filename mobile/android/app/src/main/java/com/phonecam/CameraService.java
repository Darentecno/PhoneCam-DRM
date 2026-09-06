package com.phonecam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Collections;

/**
 * Maneja la cÃ¡mara con Camera2 y la conecta directo a la Surface de entrada
 * del H264Encoder (sin pasar los frames por la app en formato YUV crudo).
 */
public class CameraService {
    private static final String TAG = "CameraService";

    // 1280x720 @ 30fps es un buen balance entre calidad y estabilidad para
    // WiFi local. Se puede subir a 1920x1080 si la red y el telÃ©fono lo soportan.
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int BITRATE = 2_000_000;

    private final Context context;
    private final CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private H264Encoder encoder;

    public CameraService(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void startCamera(H264Encoder.Listener encoderListener) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Sin permiso de cÃ¡mara");
            return;
        }

        startBackgroundThread();

        try {
            encoder = new H264Encoder(WIDTH, HEIGHT, BITRATE, encoderListener);
            Surface encoderSurface = encoder.start();

            String cameraId = getBackCameraId();
            if (cameraId == null) {
                Log.e(TAG, "No se encontrÃ³ cÃ¡mara trasera");
                return;
            }

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession(encoderSurface);
                    Log.d(TAG, "CÃ¡mara abierta correctamente");
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.d(TAG, "CÃ¡mara desconectada");
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Error en cÃ¡mara: " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);

        } catch (CameraAccessException | IOException e) {
            Log.e(TAG, "Error accediendo a cÃ¡mara o iniciando encoder", e);
        }
    }

    private void createCaptureSession(Surface encoderSurface) {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(encoderSurface);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));

            cameraDevice.createCaptureSession(Collections.singletonList(encoderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                Log.d(TAG, "SesiÃ³n de captura configurada, codificando hacia H.264");
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error configurando sesiÃ³n", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "ConfiguraciÃ³n de sesiÃ³n fallida");
                        }
                    }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creando sesiÃ³n de captura", e);
        }
    }

    public void stopCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        stopBackgroundThread();
        Log.d(TAG, "CÃ¡mara detenida");
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error deteniendo thread", e);
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private String getBackCameraId() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error obteniendo lista de cÃ¡maras", e);
        }
        return null;
    }
}
