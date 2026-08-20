package com.phonecam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import java.util.Collections;

public class CameraService {
    private static final String TAG = "CameraService";
    private final Context context;
    private final CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private boolean isRunning = false;

    public CameraService(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void startCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Sin permiso de cámara");
            return;
        }

        startBackgroundThread();

        try {
            String cameraId = getBackCameraId();
            if (cameraId == null) {
                Log.e(TAG, "No se encontró cámara trasera");
                return;
            }

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    isRunning = true;
                    createCaptureSession();
                    Log.d(TAG, "Cámara abierta correctamente");
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.d(TAG, "Cámara desconectada");
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Error en cámara: " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accediendo a cámara", e);
        }
    }

    private void createCaptureSession() {
        try {
            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    // Aquí procesarías los frames de video
                    image.close();
                }
            }, backgroundHandler);

            Surface surface = imageReader.getSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                Log.d(TAG, "Sesión de captura configurada");
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error configurando sesión", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Configuración de sesión fallida");
                        }
                    }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creando sesión de captura", e);
        }
    }

    public void stopCamera() {
        isRunning = false;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        stopBackgroundThread();
        Log.d(TAG, "Cámara detenida");
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
            Log.e(TAG, "Error obteniendo lista de cámaras", e);
        }
        return null;
    }
}