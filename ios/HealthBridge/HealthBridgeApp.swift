import SwiftUI

@main
struct HealthBridgeApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @StateObject private var vm = SyncVM()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Servidor")) {
                    TextField("URL", text: $vm.url)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    TextField("Token", text: $vm.token)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                }
                Section {
                    Button("1. Pedir permisos de Salud") {
                        Task { await vm.requestAuth() }
                    }
                    Button("2. Sincronizar ahora") {
                        Task { await vm.sync() }
                    }
                    .disabled(vm.busy)
                }
                Section(header: Text("Estado")) {
                    Text(vm.status)
                }
            }
            .navigationTitle("Health Bridge")
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active && !vm.token.isEmpty {
                Task { await vm.sync() }
            }
        }
    }
}
