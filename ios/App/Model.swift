import Dhcore
import Foundation

/// Профиль подключения. `core` — JSON ядра (ParseProfile), его же принимает туннель.
struct VpnProfile: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var kind: String
    var server: String
    var core: String

    var kindLabel: String {
        switch kind {
        case "wireguard": return "WireGuard"
        case "amneziawg": return "AmneziaWG"
        case "vless": return "VLESS"
        case "vless-reality": return "VLESS Reality"
        case "vless-xhttp": return "VLESS xhttp"
        case "trojan": return "Trojan"
        case "ss": return "Shadowsocks"
        case "vmess": return "VMess"
        case "hysteria2": return "Hysteria2"
        default: return kind
        }
    }

    /// Разбор .conf WireGuard/AmneziaWG или ссылки vless:// и т. п. Бросает с понятным текстом.
    static func parse(_ text: String, fallbackName: String) throws -> VpnProfile {
        var error: NSError?
        let core = DhcoreParseProfile(text, fallbackName, &error)
        if let error { throw error }
        guard let data = core.data(using: .utf8),
              let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "dha", code: 1, userInfo: [NSLocalizedDescriptionKey: "ядро вернуло пустой профиль"])
        }
        let name = (o["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallbackName
        return VpnProfile(id: UUID().uuidString, name: name, kind: o["kind"] as? String ?? "",
                          server: o["server"] as? String ?? "", core: core)
    }
}

/// Настройки приложения. Хранятся в UserDefaults; туннель получает профиль через
/// providerConfiguration, общий контейнер ему не нужен.
final class Store: ObservableObject {
    @Published var profiles: [VpnProfile] { didSet { save() } }
    @Published var activeId: String? { didSet { save() } }
    @Published var homeSSIDs: [String] { didSet { save() } }
    @Published var autoMode: Bool { didSet { save() } }

    private let defaults = UserDefaults.standard

    init() {
        if let data = defaults.data(forKey: "profiles"),
           let list = try? JSONDecoder().decode([VpnProfile].self, from: data) {
            profiles = list
        } else {
            profiles = []
        }
        activeId = defaults.string(forKey: "active")
        homeSSIDs = defaults.stringArray(forKey: "homeSSIDs") ?? []
        autoMode = defaults.bool(forKey: "autoMode")
    }

    var active: VpnProfile? {
        profiles.first { $0.id == activeId } ?? profiles.first
    }

    func add(_ p: VpnProfile) {
        profiles.append(p)
        if active == nil || activeId == nil { activeId = p.id }
    }

    func remove(_ p: VpnProfile) {
        profiles.removeAll { $0.id == p.id }
        if activeId == p.id { activeId = profiles.first?.id }
    }

    func addHome(_ ssid: String) {
        let s = ssid.trimmingCharacters(in: .whitespaces)
        guard !s.isEmpty, !homeSSIDs.contains(s) else { return }
        homeSSIDs.append(s)
    }

    private func save() {
        defaults.set(try? JSONEncoder().encode(profiles), forKey: "profiles")
        defaults.set(activeId, forKey: "active")
        defaults.set(homeSSIDs, forKey: "homeSSIDs")
        defaults.set(autoMode, forKey: "autoMode")
    }
}
