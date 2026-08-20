# PhoneCam

PhoneCam convierte un teléfono Android en una cámara RTSP y publica ese video
como cámara virtual de Windows para OBS Studio.

## Requisitos

- Windows 10/11
- Python 3.9 o superior
- FFmpeg instalado y disponible en `PATH`
- OBS Studio
- Android Studio para compilar e instalar la app
- macOS/Xcode para compilar la app de iPhone (opcional)

`phonecam.bat` instala las dependencias Python automáticamente. No instala
FFmpeg porque debe estar instalado como programa del sistema.

## Uso

1. Conecta teléfono y PC a la misma red WiFi (preferiblemente 5 GHz).
2. Abre `mobile/android` en Android Studio, instala la app y concede el permiso
   de cámara.
3. Abre PhoneCam en el teléfono y pulsa **Iniciar**. La app mostrará su IP y
   escuchará RTSP en el puerto `8554`.
4. Ejecuta `phonecam.bat` en el PC. El programa buscará el teléfono por mDNS,
   abrirá el stream `/live` y creará la cámara virtual **PhoneCam**.
5. En OBS añade una fuente **Dispositivo de captura de video** y selecciona
   **PhoneCam**.

Si el router bloquea mDNS, inicia el backend con la IP mostrada por el teléfono:

```bat
set PHONECAM_IP=192.168.1.25
phonecam.bat
```

La transmisión usa 1280x720 a 30 fps y aproximadamente 4 Mbps. La cámara
virtual solo existe mientras PhoneCam esté transmitiendo.

## iPhone

La app iOS está en [`ios/`](C:/Users/yeyju/Downloads/phonecam_completo/ios).
Captura H.264 con AVFoundation/VideoToolbox, expone el mismo endpoint
`rtsp://IP_DEL_IPHONE:8554/live` y anuncia `_phonecam._tcp` por Bonjour. Por
ello, el backend de Windows funciona igual que con Android.

La app iOS debe compilarse y firmarse con Xcode en macOS. Consulta
[`ios/README.md`](C:/Users/yeyju/Downloads/phonecam_completo/ios/README.md) para
generar el proyecto con XcodeGen. Una VM de macOS puede preparar la compilación,
pero la validación final necesita un iPhone físico y una conexión USB/red
funcional.
