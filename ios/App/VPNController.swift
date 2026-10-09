import Foundation
import NetworkExtension

/// Профиль VPN в системе (NETunnelProviderManager) и правила On-Demand.
///
/// На iOS «авторежим» делает сама система: правила On-Demand отключают туннель в
/// домашних SSID и подключают во всех остальных сетях — даже когда приложение закрыто.
/// Строгого режима по уровню сигнала нет: RSSI iOS приложениям не отдаёт.
@MainActor
final class VPNController: ObservableObject {
    static let tunnelBundleId = "io.github.varyen.detourhome.tunnel"

    @Published var status: NEVPNStatus = .invalid
    @Published var lastError: String?

    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?

    init() {
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: nil, queue: .main
        ) { [weak self] note in
            guard let conn = note.object as? NEVPNConnection else { return }
            Task { @MainActor in self?.status = conn.status }
        }
        Task { await load() }
    }

    func load() async {
        do {
            let all = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = all.first
            status = manager?.connection.status ?? .invalid
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Записать в систему текущий профиль и правила. Первый вызов покажет системный
    /// запрос «Добавить конфигурацию VPN».
    func apply(profile: VpnProfile?, homeSSIDs: [String], autoMode: Bool) async {
        guard let profile else {
            if let manager {
                try? await manager.removeFromPreferences()
                self.manager = nil
            }
            return
        }
        let m = manager ?? NETunnelProviderManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = Self.tunnelBundleId
        proto.serverAddress = profile.server.isEmpty ? profile.kindLabel : profile.server
        proto.providerConfiguration = ["profile": profile.core, "name": profile.name]
        m.protocolConfiguration = proto
        m.localizedDescription = "Detour Home Auto · \(profile.name)"
        m.isEnabled = true

        var rules: [NEOnDemandRule] = []
        if !homeSSIDs.isEmpty {
            let home = NEOnDemandRuleDisconnect()
            home.interfaceTypeMatch = .wiFi
            home.ssidMatch = homeSSIDs
            rules.append(home)
        }
        rules.append(NEOnDemandRuleConnect())
        m.onDemandRules = rules
        m.isOnDemandEnabled = autoMode

        do {
            try await m.saveToPreferences()
            // Без повторной загрузки первый startVPNTunnel после save падает с «configuration is stale».
            try await m.loadFromPreferences()
            manager = m
            status = m.connection.status
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    func connect() {
        do {
            try manager?.connection.startVPNTunnel()
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Отключить вручную. При включённом авторежиме система поднимет туннель снова —
    /// поэтому ручное отключение снимает и On-Demand.
    func disconnect(store: Store) async {
        if store.autoMode {
            store.autoMode = false
            await apply(profile: store.active, homeSSIDs: store.homeSSIDs, autoMode: false)
        }
        manager?.connection.stopVPNTunnel()
    }

    var statusText: String {
        switch status {
        case .connected: return "подключён"
        case .connecting: return "подключается…"
        case .disconnecting: return "отключается…"
        case .reasserting: return "переподключается…"
        case .disconnected: return "отключён"
        case .invalid: return "не настроен"
        @unknown default: return "—"
        }
    }
}
