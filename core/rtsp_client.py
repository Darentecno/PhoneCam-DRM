import subprocess
import threading
import queue
import logging
import numpy as np
import shutil

logger = logging.getLogger(__name__)

class RTSPClient:
    def __init__(self, rtsp_url: str, width: int = 1280, height: int = 720, fps: int = 30):
        self.rtsp_url = rtsp_url
        self.width = width
        self.height = height
        self.fps = fps
        self.frame_queue = queue.Queue(maxsize=2)
        self.process = None
        self.running = False
        
    def start(self):
        if self.running:
            return
        ffmpeg = shutil.which('ffmpeg')
        if ffmpeg is None:
            raise RuntimeError("FFmpeg no está instalado o no está en PATH")
        cmd = [
            ffmpeg, '-hide_banner', '-loglevel', 'warning',
            '-fflags', 'nobuffer', '-flags', 'low_delay',
            '-rtsp_transport', 'tcp', '-i', self.rtsp_url,
            '-an', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
            '-s', f'{self.width}x{self.height}', '-r', str(self.fps), '-'
        ]
        logger.info(f"Iniciando FFmpeg")
        self.process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=10**8)
        self.running = True
        threading.Thread(target=self._read_frames, daemon=True).start()
        threading.Thread(target=self._read_errors, daemon=True).start()

    def _read_errors(self):
        if self.process is None or self.process.stderr is None:
            return
        for line in iter(self.process.stderr.readline, b''):
            if line:
                logger.warning("FFmpeg: %s", line.decode(errors='replace').strip())
        
    def _read_frames(self):
        frame_size = self.width * self.height * 3
        while self.running and self.process:
            raw_frame = self.process.stdout.read(frame_size)
            if len(raw_frame) != frame_size:
                if self.running:
                    logger.error("FFmpeg terminó o entregó un frame incompleto")
                break
            frame = np.frombuffer(raw_frame, dtype=np.uint8).reshape((self.height, self.width, 3))
            if self.frame_queue.full(): self.frame_queue.get_nowait()
            self.frame_queue.put(frame)
        self.running = False
            
    def get_frame(self, timeout: float = 0.033):
        try: return self.frame_queue.get(timeout=timeout)
        except queue.Empty: return None
        
    def stop(self):
        self.running = False
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
            self.process = None