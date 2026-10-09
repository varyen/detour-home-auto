package dhcore

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/common/convert"
)

// Profile — то, что приложение хранит и отдаёт обратно в Start/Probe.
// Proxy — готовый прокси mihomo; остальное нужно платформе для VpnService/NE.
type Profile struct {
	Name   string         `json:"name"`
	Kind   string         `json:"kind"`
	Server string         `json:"server"`
	Proxy  map[string]any `json:"proxy"`
	// Маршруты в туннель. Пусто — весь трафик (0.0.0.0/0 и ::/0).
	Routes []string `json:"routes,omitempty"`
	// DNS из .conf; для полного туннеля DNS всё равно перехватывает mihomo.
	DNS []string `json:"dns,omitempty"`
	MTU int      `json:"mtu,omitempty"`
}

// FullTunnel: весь трафик идёт в туннель — значит, DNS можно перехватывать.
func (p *Profile) FullTunnel() bool {
	if len(p.Routes) == 0 {
		return true
	}
	for _, r := range p.Routes {
		if r == "0.0.0.0/0" {
			return true
		}
	}
	return false
}

// ParseProfile разбирает то, что вставил пользователь: share-ссылку
// (vless://, trojan://, ss://, hysteria2:// … — всё, что понимает mihomo)
// или .conf WireGuard / AmneziaWG. Возвращает JSON Profile.
func ParseProfile(text, fallbackName string) (string, error) {
	p, err := parseProfile(text, fallbackName)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(p)
	return string(b), err
}

func parseProfile(text, fallbackName string) (*Profile, error) {
	text = strings.TrimPrefix(strings.TrimSpace(text), string(rune(0xFEFF)))
	if text == "" {
		return nil, errors.New("пусто")
	}
	if strings.Contains(strings.ToLower(text), "[interface]") {
		return parseWgConf(text, fallbackName)
	}
	proxies, err := convert.ConvertsV2Ray([]byte(text))
	if err != nil || len(proxies) == 0 {
		return nil, errors.New("не похоже ни на ссылку (vless://, trojan://, ss://…), ни на .conf WireGuard")
	}
	px := proxies[0]
	name, _ := px["name"].(string)
	if strings.TrimSpace(name) == "" {
		name = fallbackName
	}
	if strings.TrimSpace(name) == "" {
		name = fmt.Sprint(px["type"])
	}
	px["name"] = "proxy"
	kind := fmt.Sprint(px["type"])
	if kind == "vless" {
		if n, _ := px["network"].(string); n == "xhttp" {
			kind = "vless-xhttp"
		}
		if _, ok := px["reality-opts"]; ok {
			kind = "vless-reality"
		}
	}
	p := &Profile{Name: name, Kind: kind, Server: fmt.Sprint(px["server"]), Proxy: px}
	if _, err := buildProxy(p); err != nil {
		return nil, err
	}
	return p, nil
}

// Ключи [Interface] AmneziaWG → amnezia-wg-option mihomo.
var awgKeys = map[string]string{
	"jc": "jc", "jmin": "jmin", "jmax": "jmax",
	"s1": "s1", "s2": "s2", "s3": "s3", "s4": "s4",
	"h1": "h1", "h2": "h2", "h3": "h3", "h4": "h4",
	"i1": "i1", "i2": "i2", "i3": "i3", "i4": "i4", "i5": "i5",
	"j1": "j1", "j2": "j2", "j3": "j3", "itime": "itime",
}
var awgNumeric = map[string]bool{"jc": true, "jmin": true, "jmax": true, "s1": true, "s2": true, "s3": true, "s4": true, "itime": true}

func parseWgConf(text, fallbackName string) (*Profile, error) {
	section := ""
	iface := map[string]string{}
	var peers []map[string]string
	sc := bufio.NewScanner(strings.NewReader(text))
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if i := strings.IndexAny(line, "#;"); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.Trim(line, "[] "))
			if section == "peer" {
				peers = append(peers, map[string]string{})
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k = strings.ToLower(strings.TrimSpace(k))
		v = strings.TrimSpace(v)
		switch section {
		case "interface":
			iface[k] = v
		case "peer":
			peers[len(peers)-1][k] = v
		}
	}
	if iface["privatekey"] == "" {
		return nil, errors.New("в [Interface] нет PrivateKey")
	}
	if len(peers) == 0 {
		return nil, errors.New("нет секции [Peer]")
	}
	if len(peers) > 1 {
		return nil, errors.New("несколько [Peer] не поддерживается")
	}
	peer := peers[0]
	host, port, err := splitEndpoint(peer["endpoint"])
	if err != nil {
		return nil, err
	}
	if peer["publickey"] == "" {
		return nil, errors.New("в [Peer] нет PublicKey")
	}

	px := map[string]any{
		"name":        "proxy",
		"type":        "wireguard",
		"server":      host,
		"port":        port,
		"private-key": iface["privatekey"],
		"public-key":  peer["publickey"],
		"udp":         true,
	}
	for _, a := range splitList(iface["address"]) {
		pfx, err := netip.ParsePrefix(a)
		if err != nil {
			addr, err2 := netip.ParseAddr(a)
			if err2 != nil {
				return nil, fmt.Errorf("Address %q: %v", a, err)
			}
			pfx = netip.PrefixFrom(addr, addr.BitLen())
		}
		if pfx.Addr().Is4() {
			if _, ok := px["ip"]; !ok {
				px["ip"] = pfx.String()
			}
		} else if _, ok := px["ipv6"]; !ok {
			px["ipv6"] = pfx.String()
		}
	}
	if _, ok := px["ip"]; !ok {
		if _, ok6 := px["ipv6"]; !ok6 {
			return nil, errors.New("в [Interface] нет Address")
		}
	}
	if psk := peer["presharedkey"]; psk != "" {
		px["pre-shared-key"] = psk
	}
	if ka := peer["persistentkeepalive"]; ka != "" {
		if n, err := strconv.Atoi(ka); err == nil && n > 0 {
			px["persistent-keepalive"] = n
		}
	}
	mtu := 0
	if m := iface["mtu"]; m != "" {
		if n, err := strconv.Atoi(m); err == nil && n >= 576 {
			mtu = n
			px["mtu"] = n
		}
	}
	allowed := splitList(peer["allowedips"])
	if len(allowed) == 0 {
		allowed = []string{"0.0.0.0/0", "::/0"}
	}
	px["allowed-ips"] = allowed

	var dns []string
	for _, d := range splitList(iface["dns"]) {
		if _, err := netip.ParseAddr(d); err == nil {
			dns = append(dns, d)
		}
	}
	if len(dns) > 0 {
		px["remote-dns-resolve"] = true
		px["dns"] = dns
	}

	awg := map[string]any{}
	for k, v := range iface {
		mk, ok := awgKeys[k]
		if !ok || v == "" {
			continue
		}
		if awgNumeric[k] {
			n, err := strconv.ParseInt(v, 10, 64)
			if err != nil {
				return nil, fmt.Errorf("%s = %q: нужно число", k, v)
			}
			awg[mk] = n
		} else {
			awg[mk] = v
		}
	}
	kind := "wireguard"
	if len(awg) > 0 {
		px["amnezia-wg-option"] = awg
		kind = "amneziawg"
	}

	p := &Profile{Name: fallbackName, Kind: kind, Server: host, Proxy: px, DNS: dns, MTU: mtu}
	if p.Name == "" {
		p.Name = kind
	}
	full := false
	for _, a := range allowed {
		if a == "0.0.0.0/0" {
			full = true
		}
	}
	if !full {
		p.Routes = allowed
	}
	if _, err := buildProxy(p); err != nil {
		return nil, err
	}
	return p, nil
}

func splitList(s string) []string {
	var out []string
	for _, f := range strings.Split(s, ",") {
		if f = strings.TrimSpace(f); f != "" {
			out = append(out, f)
		}
	}
	return out
}

func splitEndpoint(ep string) (string, int, error) {
	if ep == "" {
		return "", 0, errors.New("в [Peer] нет Endpoint")
	}
	i := strings.LastIndex(ep, ":")
	if i <= 0 {
		return "", 0, fmt.Errorf("Endpoint %q без порта", ep)
	}
	port, err := strconv.Atoi(ep[i+1:])
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, fmt.Errorf("Endpoint %q: неверный порт", ep)
	}
	return strings.Trim(ep[:i], "[]"), port, nil
}
