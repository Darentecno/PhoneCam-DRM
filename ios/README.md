# PhoneCam para iPhone

Esta implementación usa `AVFoundation` para capturar la cámara trasera,
`VideoToolbox` para codificar H.264 a 1280x720/30fps y un servidor RTSP TCP en
el puerto `8554`. El backend de Windows puede conectarse en
`rtsp://IP_DEL_IPHONE:8554/live`.

## Requisitos

- macOS con Xcode 15 o superior
- iPhone físico con iOS 15 o superior
- Apple ID para firmar la app (una cuenta gratuita sirve para pruebas)
- iPhone y PC en la misma red WiFi

El simulador de iOS no puede probar la cámara ni el streaming de red como un
iPhone real.

## Crear el proyecto en Xcode

Instala [XcodeGen](https://github.com/yonaskolb/XcodeGen) en la Mac y ejecuta:

```bash
cd ios
xcodegen generate
open PhoneCam.xcodeproj
```

En Xcode selecciona tu equipo de desarrollo en **Signing & Capabilities**,
conecta el iPhone y pulsa **Run**. Concede permiso de cámara y pulsa
**Iniciar**. El servicio Bonjour `_phonecam._tcp` permite que Windows lo
encuentre automáticamente; también puedes usar la IP mostrada manualmente.

Una VM de macOS en Windows no elimina las restricciones de Apple: para instalar
en un iPhone se necesita macOS/Xcode, una cuenta de Apple y normalmente
aceleración USB. La VM sirve para preparar y compilar el proyecto, pero la
prueba final debe hacerse con un iPhone conectado.
