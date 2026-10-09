import CoreLocation
import NetworkExtension

/// Имя текущей Wi-Fi. iOS отдаёт его только при entitlement'е «Access WiFi Information»
/// и выданной геолокации; без них — nil, и сеть вписывают руками.
@MainActor
final class WiFi: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var ssid: String?
    private let location = CLLocationManager()

    override init() {
        super.init()
        location.delegate = self
    }

    func refresh() {
        if location.authorizationStatus == .notDetermined {
            location.requestWhenInUseAuthorization()
        }
        NEHotspotNetwork.fetchCurrent { [weak self] net in
            Task { @MainActor in self?.ssid = net?.ssid }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in self.refresh() }
    }
}
