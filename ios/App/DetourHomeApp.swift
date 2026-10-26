import Dhcore
import SwiftUI
import UniformTypeIdentifiers

@main
struct DetourHomeApp: App {
    @StateObject private var store = Store()
    @StateObject private var vpn = VPNController()
    @StateObject private var wifi = WiFi()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(vpn)
                .environmentObject(wifi)
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var vpn: VPNController
    @EnvironmentObject var wifi: WiFi

    @State private var probes: [String: String] = [:]
    @State private var showPaste = false
    @State private var pasteText = ""
    @State private var showImporter = false
    @State private var newSSID = ""
    @State private var toast: String?
    @State private var showLog = false
    @State private var showScanner = false
    @State private var subBusy: Set<String> = []
    @State private var removingSub: VpnSubscription?

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Toggle(isOn: Binding(get: { store.autoMode }, set: { on in
                        store.autoMode = on
                        Task { await sync() }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Автоматический режим").font(.headline)
                            Text("Выключать VPN дома и включать вне дома — правилами iOS, работает и при закрытом приложении")
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                    .disabled(store.active == nil)
                }

                Section(header: Text("Статус")) {
                    row("wifi", "Текущая сеть", wifi.ssid ?? "неизвестна")
                    row("house", "Домашняя сеть", isHome ? "да" : "нет")
                    row("lock", "VPN", vpn.statusText)
                    if let p = store.active { row("key", "Профиль", "\(p.name) · \(p.kindLabel)") }
                    if let e = vpn.lastError { Text(e).font(.caption).foregroundColor(.red) }
                    HStack {
                        Button("Подключить") { Task { await sync(); vpn.connect() } }
                            .disabled(store.active == nil || vpn.status == .connected)
                        Spacer()
                        Button("Отключить", role: .destructive) { Task { await vpn.disconnect(store: store) } }
                            .disabled(vpn.status == .disconnected || vpn.status == .invalid)
                    }
                    .buttonStyle(.borderless)
                }

                Section(header: Text("Профили VPN"),
                        footer: Text("WireGuard и AmneziaWG — файлом .conf; VLESS (Reality, TLS, WebSocket, xhttp), Trojan, Shadowsocks, Hysteria2 — ссылкой. Ссылка подписки https://… добавит все профили сразу; QR-код из панели Detour и ключ Amnezia vpn:// тоже подойдут.")) {
                    ForEach(store.profiles) { p in
                        HStack {
                            Image(systemName: p.id == store.active?.id ? "largecircle.fill.circle" : "circle")
                                .foregroundColor(.accentColor)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).lineLimit(1)
                                Text([p.kindLabel, p.server, subTitle(p), probes[p.id]].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundColor(.secondary).lineLimit(2)
                            }
                            Spacer()
                            Button { probe(p) } label: { Image(systemName: "speedometer") }
                                .buttonStyle(.borderless)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            store.activeId = p.id
                            Task { await sync() }
                        }
                    }
                    .onDelete { idx in
                        idx.map { store.profiles[$0] }.forEach(store.remove)
                        Task { await sync() }
                    }
                    Button { showImporter = true } label: { Label("Файл .conf", systemImage: "doc.badge.plus") }
                    Button {
                        let clip = UIPasteboard.general.string ?? ""
                        pasteText = clip.contains("://") || clip.lowercased().contains("[interface]") ? clip : ""
                        showPaste = true
                    } label: { Label("Ссылка", systemImage: "doc.on.clipboard") }
                    Button { showScanner = true } label: { Label("Сканировать QR-код", systemImage: "qrcode.viewfinder") }
                }

                Section(header: Text("Подписки"),
                        footer: Text("Ссылка подписки из панели Detour (карточка клиента → «Подписка») или от другого сервиса. Обновляются при открытии приложения раз в 12 часов.")) {
                    ForEach(store.subscriptions) { sub in
                        HStack {
                            Image(systemName: "dot.radiowaves.up.forward").foregroundColor(.accentColor)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(sub.title).lineLimit(1)
                                Text(subLine(sub)).font(.caption)
                                    .foregroundColor(!sub.error.isEmpty && !subBusy.contains(sub.id) ? .red : .secondary)
                                    .lineLimit(2)
                            }
                            Spacer()
                            Button { refresh(sub) } label: { Image(systemName: "arrow.clockwise") }
                                .buttonStyle(.borderless)
                                .disabled(subBusy.contains(sub.id))
                        }
                    }
                    .onDelete { idx in removingSub = idx.map { store.subscriptions[$0] }.first }
                    Button {
                        let clip = UIPasteboard.general.string ?? ""
                        pasteText = VpnSubscription.isURL(clip) ? clip : ""
                        showPaste = true
                    } label: { Label("Добавить подписку", systemImage: "plus") }
                }
                .confirmationDialog("Удалить подписку?", isPresented: Binding(get: { removingSub != nil }, set: { if !$0 { removingSub = nil } }), titleVisibility: .visible) {
                    if let sub = removingSub {
                        Button("Удалить с профилями", role: .destructive) {
                            store.removeSubscription(sub.id, withProfiles: true); Task { await sync() }
                        }
                        Button("Оставить профили") {
                            store.removeSubscription(sub.id, withProfiles: false); Task { await sync() }
                        }
                    }
                }

                Section(header: Text("Домашние сети"), footer: Text("В этих сетях VPN выключается.")) {
                    ForEach(store.homeSSIDs, id: \.self) { s in
                        Label(s, systemImage: "wifi")
                    }
                    .onDelete { idx in
                        store.homeSSIDs.remove(atOffsets: idx)
                        Task { await sync() }
                    }
                    if let s = wifi.ssid, !store.homeSSIDs.contains(s) {
                        Button { store.addHome(s); Task { await sync() } } label: {
                            Label("Добавить текущую: \(s)", systemImage: "plus")
                        }
                    }
                    HStack {
                        TextField("Имя сети (SSID)", text: $newSSID)
                            .textInputAutocapitalization(.never)
                            .disableAutocorrection(true)
                        Button("Добавить") {
                            store.addHome(newSSID)
                            newSSID = ""
                            Task { await sync() }
                        }
                        .disabled(newSSID.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }

                Section {
                    Button("Журнал движка") { showLog = true }
                }
            }
            .navigationTitle("Detour Home Auto")
            .onAppear {
                wifi.refresh()
                let stale = Date().addingTimeInterval(-12 * 3600)
                store.subscriptions.filter { ($0.updatedAt ?? .distantPast) < stale }.forEach { refresh($0, quiet: true) }
            }
            .onOpenURL { url in add(url.absoluteString, name: "") }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.item]) { result in
                guard case let .success(url) = result else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
                add(text, name: url.deletingPathExtension().lastPathComponent)
            }
            .sheet(isPresented: $showPaste) { pasteSheet }
            .sheet(isPresented: $showLog) { logSheet }
            .fullScreenCover(isPresented: $showScanner) { scannerSheet }
            .alert(toast ?? "", isPresented: Binding(get: { toast != nil }, set: { if !$0 { toast = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
        .navigationViewStyle(.stack)
    }

    private var isHome: Bool { wifi.ssid.map(store.homeSSIDs.contains) ?? false }

    private var pasteSheet: some View {
        NavigationView {
            Form {
                TextEditor(text: $pasteText).frame(minHeight: 180).font(.system(.footnote, design: .monospaced))
            }
            .navigationTitle("Добавить по ссылке")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { showPaste = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Добавить") { if add(pasteText, name: "") { showPaste = false } }
                        .disabled(pasteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private var scannerSheet: some View {
        NavigationView {
            QRScannerView { code in
                showScanner = false
                add(code, name: "")
            }
            .ignoresSafeArea()
            .navigationTitle("QR-код профиля")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Отмена") { showScanner = false } } }
        }
        .navigationViewStyle(.stack)
    }

    private var logSheet: some View {
        NavigationView {
            ScrollView {
                Text(logText).font(.system(.caption2, design: .monospaced)).frame(maxWidth: .infinity, alignment: .leading).padding()
            }
            .navigationTitle("Журнал движка")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Закрыть") { showLog = false } } }
        }
    }

    /// Журнал туннеля живёт в процессе расширения; здесь — только проверки из приложения.
    private var logText: String {
        let t = DhcoreRecentLog()
        return t.isEmpty ? "Пусто. Журнал туннеля пишется в процессе расширения — смотрите Console.app по «DetourHomeTunnel»." : t
    }

    private func subTitle(_ p: VpnProfile) -> String? {
        guard let id = p.subId, let s = store.subscriptions.first(where: { $0.id == id }) else { return nil }
        return "подписка \(s.title)"
    }

    private func subLine(_ s: VpnSubscription) -> String {
        if subBusy.contains(s.id) { return "обновляю…" }
        if !s.error.isEmpty { return "ошибка: \(s.error)" }
        let n = store.profiles.filter { $0.subId == s.id }.count
        guard let t = s.updatedAt else { return "профилей \(n)" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "ru")
        return "профилей \(n) · обновлена \(f.localizedString(for: t, relativeTo: Date()))"
    }

    private func refresh(_ sub: VpnSubscription, quiet: Bool = false) {
        guard !subBusy.contains(sub.id) else { return }
        subBusy.insert(sub.id)
        DispatchQueue.global().async {
            let result = Result { try VpnSubscription.fetch(sub.url, subId: sub.id) }
            DispatchQueue.main.async {
                subBusy.remove(sub.id)
                switch result {
                case let .success(r):
                    store.apply(sub, title: r.title, fresh: r.profiles)
                    Task { await sync() }
                    if !quiet {
                        toast = "Подписка «\(r.title.isEmpty ? sub.title : r.title)»: профилей \(r.profiles.count)"
                            + (r.skipped > 0 ? ", пропущено \(r.skipped)" : "")
                    }
                case let .failure(e):
                    store.markError(sub.id, e.localizedDescription)
                    if !quiet { toast = "Подписка не обновилась: \(e.localizedDescription)" }
                }
            }
        }
    }

    @discardableResult
    private func add(_ text: String, name: String) -> Bool {
        if VpnSubscription.isURL(text) {
            let url = text.trimmingCharacters(in: .whitespacesAndNewlines)
            let sub = store.subscriptions.first { $0.url == url }
                ?? VpnSubscription(id: UUID().uuidString, url: url, title: URL(string: url)?.host ?? url, updatedAt: nil)
            refresh(sub)
            return true
        }
        do {
            let p = try VpnProfile.parse(text, fallbackName: name)
            store.add(p)
            Task { await sync() }
            toast = "Добавлен профиль: \(p.name) (\(p.kindLabel))"
            return true
        } catch {
            toast = "Не разобрал: \(error.localizedDescription)"
            return false
        }
    }

    private func probe(_ p: VpnProfile) {
        probes[p.id] = "проверка…"
        let core = p.core
        DispatchQueue.global().async {
            var ms: Int32 = 0
            var err: NSError?
            let text = DhcoreProbe(core, "", 10000, &ms, &err)
                ? "\(ms) мс"
                : "ошибка: \(err?.localizedDescription ?? "нет ответа")"
            DispatchQueue.main.async { probes[p.id] = text }
        }
    }

    private func sync() async {
        await vpn.apply(profile: store.active, homeSSIDs: store.homeSSIDs, autoMode: store.autoMode)
    }

    private func row(_ icon: String, _ label: String, _ value: String) -> some View {
        HStack {
            Image(systemName: icon).foregroundColor(.accentColor).frame(width: 22)
            Text(label)
            Spacer()
            Text(value).foregroundColor(.secondary).lineLimit(1)
        }
    }
}
