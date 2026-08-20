import logging
import numpy as np

try:
    import pyvirtualcam
    PYVIRTUALCAM_AVAILABLE = True
except ImportError:
    PYVIRTUALCAM_AVAILABLE = False

logger = logging.getLogger(__name__)

class VirtualCamera:
    def __init__(self, width: int = 1280, height: int = 720, fps: int = 30):
        self.width = width
        self.height = height
        self.fps = fps
        self.cam = None
        self.running = False
        
    def start(self) -> bool:
        if not PYVIRTUALCAM_AVAILABLE:
            logger.error("❌ pyvirtualcam no disponible")
            return False
            
        try:
            self.cam = pyvirtualcam.Camera(
                width=self.width,
                height=self.height,
                fps=self.fps,
                fmt=pyvirtualcam.PixelFormat.RGB
            )
            self.running = True
            logger.info(f"✅ Cámara virtual iniciada: {self.cam.device}")
            return True
        except Exception:
            logger.exception("No se pudo crear la cámara virtual")
            return False
            
    def send_frame(self, frame: np.ndarray):
        if not self.running or self.cam is None:
            return
        if frame.shape[:2] != (self.height, self.width):
            logger.error("Frame incompatible: %sx%s (se esperaba %sx%s)",
                         frame.shape[1], frame.shape[0], self.width, self.height)
            self.stop()
            return
        try:
            self.cam.send(frame)
            self.cam.sleep_until_next_frame()
        except Exception:
            logger.exception("Error enviando frame a la cámara virtual")
            self.stop()
            
    def stop(self):
        self.running = False
        if self.cam: self.cam.close(); self.cam = None
        logger.info("⏹️ Cámara virtual detenida")
