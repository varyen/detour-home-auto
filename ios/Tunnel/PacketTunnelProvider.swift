// Туннель Detour Home Auto на iOS: отдельный процесс-расширение, системный utun
// достаётся только ему. Внутри — то же ядро на mihomo, что на Android. Собственные
// сокеты расширения в его же туннель не заходят, «защищать» их не нужно.

import Dhcore
import Foundation
import NetworkExtension

final class PacketTunnelProvider: NEPacketTunnelProvider {

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        guard let proto = protocolConfiguration as? NETunnelProviderProtocol,
              let core = proto.providerConfiguration?["profile"] as? String,
              let data = core.data(using: .utf8),
              let profile = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            completionHandler(failure("в конфигурации VPN нет профиля — откройте приложение"))
            return
        }
        let routes = profile["routes"] as? [String] ?? []
        let full = routes.isEmpty || routes.contains("0.0.0.0/0")
        let mtu = (profile["mtu"] as? NSNumber)?.intValue ?? Int(DhcoreDefaultMTU)

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: DhcoreTunDNS4)
        settings.mtu = NSNumber(value: mtu)
        let v4 = NEIPv4Settings(addresses: [DhcoreTunAddress4], subnetMasks: ["255.255.255.252"])
        let v6 = NEIPv6Settings(addresses: [DhcoreTunAddress6], networkPrefixLengths: [NSNumber(value: DhcoreTunPrefix6)])
        if full {
            v4.includedRoutes = [NEIPv4Route.default()]
            // IPv6 заводим в туннель, чтобы он не шёл мимо; движок его отбрасывает.
            v6.includedRoutes = [NEIPv6Route.default()]
            let dns = NEDNSSettings(servers: [DhcoreTunDNS4])
            dns.matchDomains = [""]
            settings.dnsSettings = dns
        } else {
            v4.includedRoutes = routes.compactMap(route4)
            v6.includedRoutes = routes.compactMap(route6)
            if let servers = profile["dns"] as? [String], !servers.isEmpty {
                settings.dnsSettings = NEDNSSettings(servers: servers)
            }
        }
        settings.ipv4Settings = v4
        settings.ipv6Settings = v6

        setTunnelNetworkSettings(settings) { error in
            if let error {
                completionHandler(error)
                return
            }
            let fd = DhcoreTunnelFileDescriptor()
            guard fd >= 0 else {
                completionHandler(failure("система не отдала дескриптор туннеля"))
                return
            }
            let home = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("core", isDirectory: true)
            var err: NSError?
            if DhcoreStart(core, fd, home.path, &err) {
                completionHandler(nil)
            } else {
                NSLog("dha: ядро не запустилось: \(String(describing: err))\n\(DhcoreRecentLog())")
                completionHandler(err ?? failure("ядро не запустилось"))
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        DhcoreStop()
        completionHandler()
    }

    /// Приложение может спросить журнал ядра: оно живёт в этом процессе.
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        completionHandler?(DhcoreRecentLog().data(using: .utf8))
    }
}

private func failure(_ text: String) -> NSError {
    NSError(domain: "dha", code: 1, userInfo: [NSLocalizedDescriptionKey: text])
}

private func route4(_ cidr: String) -> NEIPv4Route? {
    let parts = cidr.split(separator: "/")
    guard let addr = parts.first, addr.contains("."), let len = Int(parts.count > 1 ? String(parts[1]) : "32"),
          (0...32).contains(len) else { return nil }
    let mask = len == 0 ? UInt32(0) : UInt32.max << (32 - UInt32(len))
    let m = [24, 16, 8, 0].map { String((mask >> UInt32($0)) & 0xff) }.joined(separator: ".")
    return NEIPv4Route(destinationAddress: String(addr), subnetMask: m)
}

private func route6(_ cidr: String) -> NEIPv6Route? {
    let parts = cidr.split(separator: "/")
    guard let addr = parts.first, addr.contains(":"), let len = Int(parts.count > 1 ? String(parts[1]) : "128") else { return nil }
    return NEIPv6Route(destinationAddress: String(addr), networkPrefixLength: NSNumber(value: len))
}
