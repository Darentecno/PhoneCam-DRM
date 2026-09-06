# PhoneCam-DRM

PhoneCam convierte tu dispositivo móvil (Android / iOS) en una cámara web inalámbrica para OBS Studio, transmitiendo video en tiempo real mediante el protocolo RTSP a través de tu red local.

## Requisitos del Sistema y Enlaces de Descarga

Para asegurar el funcionamiento correcto del proyecto, asegúrate de tener instaladas las siguientes herramientas en tu entorno de desarrollo:

- **Sistema Operativo:** Windows 10 o 11.
- **Python (Versión 3.9 o superior):** [Descargar Python](https://www.python.org/downloads/).
- **FFmpeg:** Es indispensable que esté instalado a nivel de sistema y configurado en las variables de entorno (`PATH`). [Descargar FFmpeg](https://ffmpeg.org/download.html).
- **OBS Studio:** [Descargar OBS Studio](https://obsproject.com/).
- **Android Studio:** Necesario para compilar y desplegar la aplicación móvil en dispositivos Android. [Descargar Android Studio](https://developer.android.com/studio).
- **Xcode y macOS:** Requisito opcional únicamente si requieres compilar la aplicación para iPhone.

El script `phonecam.bat` incluido en el repositorio se encarga de instalar automáticamente las dependencias necesarias de Python (como `opencv-python` y `pyvirtualcam`).

---

## Guía de Uso Paso a Paso

1. **Conexión de Red:** Conecta tanto tu teléfono móvil como tu PC a la misma red Wi-Fi (se recomienda encarecidamente utilizar la banda de **5 GHz** para minimizar la latencia).
2. **Configuración en Android:** Abre la carpeta `mobile/android` en Android Studio, compila e instala la aplicación en tu dispositivo, y otorga los permisos necesarios para el uso de la cámara.
3. **Inicio del Servidor RTSP:** Abre la aplicación PhoneCam en el celular y presiona **Iniciar**. La interfaz mostrará la dirección IP local asignada y el servidor comenzará a escuchar conexiones en el puerto `8554`.
4. **Ejecución del Backend:** Ejecuta el archivo `phonecam.bat` en tu PC. El script buscará automáticamente el dispositivo en la red mediante mDNS, abrirá el flujo de video `/live` y registrará la cámara virtual del sistema bajo el nombre **PhoneCam**.
5. **Visualización en OBS Studio:**
   - **Método con Cámara Virtual:** Añade una fuente de tipo **Dispositivo de captura de video** en OBS y selecciona **PhoneCam**.
   - **Método Directo (Recomendado para baja latencia):** Añade una **Fuente multimedia** en OBS, desmarca la opción "Archivo local", escribe en el campo de entrada `rtsp://<IP_DE_TU_TELEFONO>:8554/live` y añade el parámetro `rtsp_transport=tcp` en las opciones de entrada (`Input Flags`) para estabilizar el flujo de red.

 ## Soporte para Dispositivos iOS (iPhone)

La aplicación para iOS captura video H.264 utilizando los componentes nativos AVFoundation y VideoToolbox. Al operar bajo el mismo protocolo RTSP y anunciar su presencia mediante Bonjour (`_phonecam._tcp`), se integra con el backend de OBS en Windows exactamente igual que la versión de Android.

### 1. Entorno de Compilación (macOS)
Para instalar la aplicación en tu iPhone, es **obligatorio** el uso de Xcode, una herramienta que solo está disponible en el sistema operativo macOS.

* **Si tienes un ordenador Apple (Mac):** No necesitas descargar ninguna máquina virtual. Tu equipo ya cuenta con el entorno nativo; simplemente descarga **Xcode** de forma gratuita desde la Mac App Store.
* **Si usas Windows:** Debes configurar una **Máquina Virtual (VM)** con macOS (utilizando software como VMware Workstation Player o VirtualBox). 
  **Requisitos mínimos para la Máquina Virtual:**
  * Virtualización por hardware (VT-x o AMD-V) habilitada en la BIOS de tu PC.
  * Mínimo **8 GB de memoria RAM** asignados exclusivamente a la VM (se recomiendan 16 GB para compilar sin bloqueos).
  * Al menos **60 GB de espacio libre** en el disco (preferiblemente en un disco SSD).
  * **Soporte USB Passthrough (Redirección USB):** Fundamental para que, al conectar el iPhone por cable a tu PC con Windows, la máquina virtual de macOS logre detectarlo físicamente.

### 2. Paso a paso para compilar y transmitir

1. **Generar el proyecto de Xcode:** Abre la terminal en macOS (ya sea en tu Mac nativo o en la VM), navega hasta la carpeta `ios/` del proyecto y sigue las instrucciones detalladas en `ios/README.md` para generar la estructura del proyecto haciendo uso de **XcodeGen**.
2. **Conectar el dispositivo:** Conecta tu iPhone físico mediante cable USB. *(Nota importante: La cámara no puede emularse; la validación final y la transmisión requieren obligatoriamente un dispositivo físico, no el simulador de Xcode)*.
3. **Firmar y compilar la app:** Abre el proyecto generado en Xcode. Dirígete a la pestaña *Signing & Capabilities*, ingresa tu cuenta de Apple (Apple ID) para firmar el código, selecciona tu iPhone en la lista de dispositivos de destino y haz clic en **Build and Run** (botón de Play).
4. **Iniciar la transmisión RTSP:** Una vez instalada, abre PhoneCam en tu iPhone. Concede los permisos de Cámara y Red Local y presiona **Iniciar**. La pantalla mostrará el endpoint activo, por ejemplo: `rtsp://<IP_DEL_IPHONE>:8554/live`.
5. **Conectar en OBS Studio (Windows):** Regresa a tu PC principal.
   * **Método Directo:** Añade una **Fuente multimedia** en OBS, pega la ruta RTSP del iPhone y añade `rtsp_transport=tcp` en las Opciones de entrada.
   * **Método Cámara Virtual:** Ejecuta `phonecam.bat` para que el script detecte el iPhone y encienda la cámara virtual del sistema.

### Solución alternativa si el router bloquea mDNS
Si la red local impide el descubrimiento automático por mDNS, puedes forzar la conexión iniciando el backend especificando de forma manual la dirección IP que muestra tu teléfono:

```bat
set PHONECAM_IP=192.168.x.xx
phonecam.bat

