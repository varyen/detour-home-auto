import Dhcore
import Foundation

/// Профиль подключения. `core` — JSON ядра (ParseProfile), его же принимает туннель.
struct VpnProfile: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var kind: String
    var server: String
    var core: String
    /// id подписки, из которой пришёл профиль; nil — добавлен вручную.
    var subId: String? = nil

    var kindLabel: String {
        switch kind {
        case "wireguard": return "WireGuard"
        case "amneziawg": return "AmneziaWG"
        case "vless": return "VLESS"
        case "vless-reality": return "VLESS Reality"
        case "vless-xhttp": return "VLESS xhttp"
        case "vless-ws": return "VLESS WebSocket"
        case "vless-tls": return "VLESS TLS"
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

/// Подписка: ссылка, по которой сервер отдаёт все профили сразу.
struct VpnSubscription: Codable, Identifiable, Equatable {
    var id: String
    var url: String
    var title: String
    var updatedAt: Date?
    var error: String = ""

    static func isURL(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return (t.hasPrefix("https://") || t.hasPrefix("http://")) && !t.contains("\n")
    }

    /// Сеть — только не на главном потоке. Профили — ещё без постоянных id.
    static func fetch(_ url: String, subId: String) throws -> (title: String, profiles: [VpnProfile], skipped: Int) {
        var error: NSError?
        let js = DhcoreFetchSubscription(url.trimmingCharacters(in: .whitespacesAndNewlines), &error)
        if let error { throw error }
        guard let data = js.data(using: .utf8),
              let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "dha", code: 2, userInfo: [NSLocalizedDescriptionKey: "ядро вернуло пустую подписку"])
        }
        let list = (o["profiles"] as? [[String: Any]] ?? []).compactMap { p -> VpnProfile? in
            guard let d = try? JSONSerialization.data(withJSONObject: p),
                  let core = String(data: d, encoding: .utf8) else { return nil }
            let kind = p["kind"] as? String ?? ""
            let name = (p["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? kind
            return VpnProfile(id: UUID().uuidString, name: name, kind: kind,
                              server: p["server"] as? String ?? "", core: core, subId: subId)
        }
        return (o["title"] as? String ?? "", list, o["skipped"] as? Int ?? 0)
    }
}

/// Настройки приложения. Хранятся в UserDefaults; туннель получает профиль через
/// providerConfiguration, общий контейнер ему не нужен.
final class Store: ObservableObject {
    @Published var profiles: [VpnProfile] { didSet { save() } }
    @Published var activeId: String? { didSet { save() } }
    @Published var homeSSIDs: [String] { didSet { save() } }
    @Published var autoMode: Bool { didSet { save() } }
    @Published var subscriptions: [VpnSubscription] { didSet { save() } }

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
        if let data = defaults.data(forKey: "subscriptions"),
           let list = try? JSONDecoder().decode([VpnSubscription].self, from: data) {
            subscriptions = list
        } else {
            subscriptions = []
        }
    }

    /// Профили подписки заменяются целиком; id сохраняется у профиля с тем же
    /// именем и видом, чтобы не сбрасывался выбранный.
    func apply(_ sub: VpnSubscription, title: String, fresh: [VpnProfile]) {
        var keep: [String: String] = [:]
        for p in profiles where p.subId == sub.id { keep[p.name + "|" + p.kind] = p.id }
        let merged = fresh.map { p -> VpnProfile in
            var q = p
            if let id = keep[p.name + "|" + p.kind] { q.id = id }
            return q
        }
        profiles = profiles.filter { $0.subId != sub.id } + merged
        var s = sub
        if !title.isEmpty { s.title = title }
        s.updatedAt = Date()
        s.error = ""
        subscriptions = subscriptions.filter { $0.id != sub.id } + [s]
        if !profiles.contains(where: { $0.id == activeId }) { activeId = profiles.first?.id }
    }

    func markError(_ id: String, _ error: String) {
        subscriptions = subscriptions.map { var s = $0; if s.id == id { s.error = error }; return s }
    }

    func removeSubscription(_ id: String, withProfiles: Bool) {
        subscriptions.removeAll { $0.id == id }
        if withProfiles {
            profiles.removeAll { $0.subId == id }
        } else {
            profiles = profiles.map { var p = $0; if p.subId == id { p.subId = nil }; return p }
        }
        if !profiles.contains(where: { $0.id == activeId }) { activeId = profiles.first?.id }
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
        defaults.set(try? JSONEncoder().encode(subscriptions), forKey: "subscriptions")
    }
}
