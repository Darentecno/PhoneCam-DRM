#!/usr/bin/env python3
import sys
import logging
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.discovery import MDNSDiscovery
from core.rtsp_client import RTSPClient
from core.virtual_camera import VirtualCamera

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def main():
    logger.info("🔍 Buscando dispositivos PhoneCam...")
    discovery = MDNSDiscovery()
    devices = discovery.discover(timeout=5)
    
    if not devices:
        logger.error("❌ No se encontraron dispositivos")
        return
    
    logger.info("📱 Dispositivos encontrados:")
    for i, device in enumerate(devices):
        logger.info(f"  {i+1}. {device['name']} ({device['ip']}:{device['port']})")
    
    selected = devices[0]
    rtsp_url = f"rtsp://{selected['ip']}:{selected['port']}/live"
    logger.info(f"🔗 Conectando a: {rtsp_url}")
    
    client = RTSPClient(rtsp_url)
    client.start()
    
    vcam = VirtualCamera()
    vcam.start()
    
    logger.info("✅ Transmitiendo a cámara virtual")
    
    try:
        while True:
            frame = client.get_frame(timeout=0.033)
            if frame is not None:
                vcam.send_frame(frame)
    except KeyboardInterrupt:
        pass
    finally:
        client.stop()
        vcam.stop()
        discovery.stop()

if __name__ == "__main__":
    main()
