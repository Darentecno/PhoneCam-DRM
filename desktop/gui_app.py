import sys
import threading
import tkinter as tk
from tkinter import ttk
from pathlib import Path
import logging
import os

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.discovery import MDNSDiscovery
from core.rtsp_client import RTSPClient
from core.virtual_camera import VirtualCamera

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class PhoneCamApp:
    def __init__(self, root):
        self.root = root
        self.root.title("📱 PhoneCam - Cámara Virtual")
        self.root.geometry("700x650")
        self.root.configure(bg='#f0f0f0')
        
        self.discovery = None
        self.client = None
        self.vcam = None
        self.running = False
        
        self.setup_ui()
        
    def setup_ui(self):
        # Título
        title = tk.Label(self.root, text="📱 PhoneCam", font=("Arial", 32, "bold"), fg="#2196F3", bg='#f0f0f0')
        title.pack(pady=15)
        
        subtitle = tk.Label(self.root, text="Convierte tu teléfono en cámara web", font=("Arial", 12), bg='#f0f0f0')
        subtitle.pack()

        # Entrada manual de IP
        frame_ip = tk.LabelFrame(self.root, text=" Configuración de Conexión ", font=("Arial", 11, "bold"), bg='#f0f0f0', padx=10, pady=10)
        frame_ip.pack(fill="x", padx=20, pady=10)

        lbl_ip_prompt = tk.Label(frame_ip, text="IP del teléfono (ej: 192.168.1.15):", font=("Arial", 10), bg='#f0f0f0')
        lbl_ip_prompt.pack(side="left", padx=5)

        self.ip_entry = tk.Entry(frame_ip, font=("Arial", 11), width=20)
        self.ip_entry.pack(side="left", padx=5)
        # Carga IP previa o variable de entorno si existe
        self.ip_entry.insert(0, os.environ.get("PHONECAM_IP", ""))
        
        # Estado
        frame_estado = tk.LabelFrame(self.root, text=" Estado ", font=("Arial", 11, "bold"), bg='#f0f0f0', padx=10, pady=10)
        frame_estado.pack(fill="x", padx=20, pady=5)
        
        self.status_label = tk.Label(frame_estado, text="⏸️ Detenido", font=("Arial", 14), fg="#666", bg='#f0f0f0')
        self.status_label.pack()
        
        self.device_label = tk.Label(frame_estado, text="📱 Dispositivo: No conectado", font=("Arial", 10), bg='#f0f0f0')
        self.device_label.pack(pady=5)
        
        self.ip_label = tk.Label(frame_estado, text="🌐 IP: ---", font=("Arial", 9), bg='#f0f0f0', fg="#888")
        self.ip_label.pack()
        
        # Control
        frame_control = tk.LabelFrame(self.root, text=" Control ", font=("Arial", 11, "bold"), bg='#f0f0f0', padx=10, pady=10)
        frame_control.pack(fill="x", padx=20, pady=5)
        
        self.start_btn = tk.Button(frame_control, text="▶️ Iniciar", command=self.start_stream, 
                                   bg="#4CAF50", fg="white", font=("Arial", 12), height=2, width=20)
        self.start_btn.pack(side="left", padx=5, expand=True)
        
        self.stop_btn = tk.Button(frame_control, text="⏹️ Detener", command=self.stop_stream, 
                                  bg="#f44336", fg="white", font=("Arial", 12), height=2, width=20, state="disabled")
        self.stop_btn.pack(side="left", padx=5, expand=True)
        
        # Log
        frame_log = tk.LabelFrame(self.root, text=" Log ", font=("Arial", 11, "bold"), bg='#f0f0f0', padx=10, pady=10)
        frame_log.pack(fill="both", expand=True, padx=20, pady=10)
        
        self.log_text = tk.Text(frame_log, height=8, font=("Consolas", 9), bg='#1e1e1e', fg='#d4d4d4', insertbackground='white')
        scrollbar = tk.Scrollbar(frame_log, command=self.log_text.yview)
        self.log_text.config(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.log_text.pack(fill="both", expand=True)
        
        self.log("🎯 PhoneCam iniciado")
        self.log("📱 Asegúrate que la app móvil esté ejecutándose")
        
    def log(self, message):
        self.root.after(0, self._append_log, message)

    def _append_log(self, message):
        self.log_text.insert(tk.END, f"{message}\n")
        self.log_text.see(tk.END)
        
    def start_stream(self):
        self.start_btn.config(state="disabled")
        self.stop_btn.config(state="normal")
        self.status_label.config(text="🔄 Buscando...", fg="#FF9800")
        self.log("🔍 Conectando dispositivo...")
        threading.Thread(target=self._start_stream, daemon=True).start()
        
    def _start_stream(self):
        try:
            manual_ip = self.ip_entry.get().strip()
            devices = []

            # Si el usuario escribió una IP manualmente, la usa directo
            if manual_ip:
                self.log(f"📌 Usando IP manual: {manual_ip}")
                devices = [{"name": "PhoneCam (manual)", "ip": manual_ip, "port": 8554}]
            else:
                self.log("🔍 Buscando automáticamente vía mDNS...")
                self.discovery = MDNSDiscovery()
                devices = self.discovery.discover(timeout=5)
            
            if not devices:
                self.log("❌ No se encontraron dispositivos. Si mDNS falla, ingresa la IP manualmente en la casilla de arriba.")
                self.root.after(0, self.stop_stream)
                return
            
            self.log(f"✅ Dispositivo listo")
            device = devices[0]
            
            self.root.after(0, lambda: self.device_label.config(text=f"📱 Dispositivo: {device['name']}"))
            self.root.after(0, lambda: self.ip_label.config(text=f"🌐 IP: {device['ip']}:{device['port']}"))
            
            rtsp_url = f"rtsp://{device['ip']}:{device['port']}/live"
            self.log(f"🔗 Conectando a: {rtsp_url}")
            
            self.client = RTSPClient(rtsp_url)
            self.client.start()
            self.log("✅ Cliente RTSP conectado")
            
            self.vcam = VirtualCamera(width=self.client.width, height=self.client.height, fps=self.client.fps)
            if not self.vcam.start():
                self.log("❌ Error iniciando cámara virtual")
                self.root.after(0, self.stop_stream)
                return
            
            self.running = True
            self.root.after(0, lambda: self.status_label.config(text="📹 Transmitiendo", fg="#4CAF50"))
            self.log("✅ ¡Transmisión iniciada!")
            self.log("📺 Abre OBS Studio y selecciona 'PhoneCam'")
            
            while self.running and self.client.running and self.vcam.running:
                frame = self.client.get_frame(timeout=0.033)
                if frame is not None:
                    self.vcam.send_frame(frame)
            if self.running:
                raise RuntimeError("La conexión RTSP se cerró")
                    
        except Exception as e:
            self.log(f"❌ Error: {e}")
            self.root.after(0, self.stop_stream)
            
    def stop_stream(self):
        self.running = False
        if self.client: self.client.stop()
        if self.vcam: self.vcam.stop()
        if self.discovery: self.discovery.stop()
        
        self.root.after(0, lambda: self.status_label.config(text="⏸️ Detenido", fg="#666"))
        self.root.after(0, lambda: self.device_label.config(text="📱 Dispositivo: No conectado"))
        self.root.after(0, lambda: self.start_btn.config(state="normal"))
        self.root.after(0, lambda: self.stop_btn.config(state="disabled"))
        self.log("⏹️ Transmisión detenida")

def main():
    root = tk.Tk()
    app = PhoneCamApp(root)
    root.mainloop()

if __name__ == "__main__":
    main()