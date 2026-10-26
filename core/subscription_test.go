package dhcore

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

const uuid = "11111111-2222-3333-4444-555555555555"

// Ссылки в том виде, в каком их отдаёт свой VPN-сервер Detour (2.17).
var detourLinks = map[string]string{
	"vless-reality": "vless://" + uuid + "@vpn.example.com:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=zSjoYegTv_-KvaABVe3JoAYObLeJZPAqmIiHzB7p_wA&sid=79bd4478da9492c0&type=tcp#a",
	"vless-tls":     "vless://" + uuid + "@vpn.example.com:4430?encryption=none&flow=xtls-rprx-vision&security=tls&sni=vpn.example.com&fp=safari&type=tcp#b",
	"vless-ws":      "vless://" + uuid + "@vpn.example.com:8443?encryption=none&security=tls&sni=vpn.example.com&fp=chrome&type=ws&host=vpn.example.com&path=%2Fa8dcb73e50d633ee#c",
	"vless-xhttp":   "vless://" + uuid + "@vpn.example.com:8443?encryption=none&security=tls&sni=vpn.example.com&fp=chrome&type=xhttp&host=vpn.example.com&path=%2F56cbaebf2493253c&mode=auto#d",
}

func TestDetourLinks(t *testing.T) {
	for want, link := range detourLinks {
		p, err := parseProfile(link, "")
		if err != nil {
			t.Fatalf("%s: %v", want, err)
		}
		if p.Kind != want {
			t.Errorf("%s: kind %q", want, p.Kind)
		}
	}
	p, _ := parseProfile(detourLinks["vless-ws"], "")
	ws, _ := p.Proxy["ws-opts"].(map[string]any)
	if ws == nil || ws["path"] != "/a8dcb73e50d633ee" {
		t.Errorf("ws-opts: %v", p.Proxy["ws-opts"])
	}
	p, _ = parseProfile(detourLinks["vless-tls"], "")
	if p.Proxy["client-fingerprint"] != "safari" || p.Proxy["flow"] != "xtls-rprx-vision" {
		t.Errorf("tls: fp %v flow %v", p.Proxy["client-fingerprint"], p.Proxy["flow"])
	}
}

func TestSubscriptionURIList(t *testing.T) {
	var lines []string
	for _, l := range detourLinks {
		lines = append(lines, l)
	}
	b64 := base64.StdEncoding.EncodeToString([]byte(strings.Join(lines, "\n")))
	s, err := parseSubscription(b64)
	if err != nil {
		t.Fatal(err)
	}
	if len(s.Profiles) != 4 {
		t.Fatalf("профилей %d", len(s.Profiles))
	}
}

// Формат clash, как его отдаёт подписка Detour: тут же AmneziaWG.
func TestSubscriptionClash(t *testing.T) {
	yml := `proxies:
  - name: "phone · VLESS XHTTP"
    type: vless
    server: vpn.example.com
    port: 8443
    uuid: ` + uuid + `
    network: xhttp
    udp: true
    tls: true
    servername: vpn.example.com
    client-fingerprint: chrome
    xhttp-opts:
      path: "/56cbaebf2493253c"
      host: vpn.example.com
  - name: "phone · VLESS WS"
    type: vless
    server: vpn.example.com
    port: 8443
    uuid: ` + uuid + `
    network: ws
    udp: true
    tls: true
    servername: vpn.example.com
    client-fingerprint: chrome
    ws-opts:
      path: "/a8dcb73e50d633ee"
      headers:
        Host: vpn.example.com
  - name: "phone · AmneziaWG"
    type: wireguard
    server: vpn.example.com
    port: 51820
    ip: 10.66.0.3
    private-key: "` + key() + `"
    public-key: "` + key() + `"
    pre-shared-key: "` + key() + `"
    mtu: 1376
    udp: true
    persistent-keepalive: 25
    remote-dns-resolve: true
    dns: [10.66.0.1]
    allowed-ips: [0.0.0.0/0, "::/0"]
    amnezia-wg-option:
      jc: 10
      jmin: 69
      jmax: 625
      s1: 73
      s2: 15
      h1: "144834485"
      h2: "832756960"
      h3: "1695133400"
      h4: "1726202119"
  - name: "odd"
    type: unknown-proto
    server: x
    port: 1
proxy-groups:
  - name: "phone"
    type: select
    proxies:
      - "phone · VLESS WS"
rules:
  - MATCH,phone
`
	s, err := parseSubscription(yml)
	if err != nil {
		t.Fatal(err)
	}
	kinds := map[string]bool{}
	for _, p := range s.Profiles {
		kinds[p.Kind] = true
		if _, err := json.Marshal(p); err != nil {
			t.Fatalf("%s: %v", p.Name, err)
		}
	}
	for _, k := range []string{"vless-xhttp", "vless-ws", "amneziawg"} {
		if !kinds[k] {
			t.Errorf("нет %s: %v", k, kinds)
		}
	}
	if s.Skipped != 1 {
		t.Errorf("skipped %d", s.Skipped)
	}
	for _, p := range s.Profiles {
		if p.Kind == "amneziawg" && (p.MTU != 1376 || len(p.DNS) != 1 || len(p.Routes) != 0) {
			t.Errorf("awg: mtu %d dns %v routes %v", p.MTU, p.DNS, p.Routes)
		}
		if p.Name == "proxy" {
			t.Errorf("имя потеряно")
		}
	}
}
