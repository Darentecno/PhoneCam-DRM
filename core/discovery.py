import socket
import logging
from zeroconf import ServiceBrowser, ServiceInfo, Zeroconf
import threading

logger = logging.getLogger(__name__)

class MDNSDiscovery:
    def __init__(self, service_type: str = "_phonecam._tcp.local."):
        self.service_type = service_type
        self.zeroconf = Zeroconf()
        # Se inicializa el Lock antes de usarlo en el bloque 'with'
        self._lock = threading.Lock()
        with self._lock:
            self.devices = []
        self._discovery_complete = threading.Event()
        
    def discover(self, timeout: int = 5):
        self.devices = []
        self._discovery_complete.clear()
        
        class DeviceListener:
            def __init__(self, parent): 
                self.parent = parent
            def add_service(self, zc, type_, name):
                info = zc.get_service_info(type_, name)
                if info: 
                    self.parent._add_device(info, name)
            def remove_service(self, zc, type_, name): 
                pass
            def update_service(self, zc, type_, name):
                info = zc.get_service_info(type_, name)
                if info: 
                    self.parent._add_device(info, name)
        
        ServiceBrowser(self.zeroconf, self.service_type, DeviceListener(self))
        self._discovery_complete.wait(timeout)
        return self.devices.copy()
    
    def _add_device(self, info: ServiceInfo, name: str):
        with self._lock:
            addresses = [socket.inet_ntoa(addr) for addr in info.addresses
                         if len(addr) == 4]
            device = {
                'name': name,
                'ip': addresses[0] if addresses else None,
                'port': info.port
            }
            if device['ip'] is None:
                return
            if not any(d['ip'] == device['ip'] and d['port'] == device['port']
                       for d in self.devices):
                self.devices.append(device)
            self._discovery_complete.set()
    
    def stop(self):
        self.zeroconf.close()