import SwiftUI

struct ContentView: View {
    @StateObject private var model = StreamModel()

    var body: some View {
        VStack(spacing: 18) {
            Text("PhoneCam").font(.largeTitle.bold())
            Text(model.isStreaming ? "Transmitiendo" : "Detenido")
                .foregroundStyle(model.isStreaming ? .green : .secondary)
            Text("rtsp://\(model.ipAddress):8554/live")
                .font(.footnote.monospaced())
                .textSelection(.enabled)
            Button(model.isStreaming ? "Detener" : "Iniciar") {
                model.isStreaming ? model.stop() : model.start()
            }
            .buttonStyle(.borderedProminent)
            Text(model.errorMessage ?? "Conecta el iPhone y el PC a la misma WiFi.")
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
        .padding(24)
    }
}
