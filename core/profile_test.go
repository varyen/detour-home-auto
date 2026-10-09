package dhcore

import (
	"bytes"
	"compress/zlib"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"testing"
)

func key() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.StdEncoding.EncodeToString(b)
}

func awgConf() string {
	return "[Interface]\nPrivateKey = " + key() + "\nAddress = 10.66.0.2/32\nDNS = 1.1.1.1\n" +
		"Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\nS2 = 21\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n\n" +
		"[Peer]\nPublicKey = " + key() + "\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0, ::/0\n"
}

// Ключ как у AmneziaVPN: qCompress (длина BE + zlib) JSON-а, конфиг — во вложенной JSON-строке.
func amneziaKey(t *testing.T, conf string) string {
	inner, _ := json.Marshal(map[string]any{"config": conf})
	outer, _ := json.Marshal(map[string]any{
		"containers": []any{map[string]any{"awg": map[string]any{"last_config": string(inner)}}},
	})
	var z bytes.Buffer
	w := zlib.NewWriter(&z)
	_, _ = w.Write(outer)
	_ = w.Close()
	buf := make([]byte, 4, 4+z.Len())
	binary.BigEndian.PutUint32(buf, uint32(len(outer)))
	buf = append(buf, z.Bytes()...)
	return "vpn://" + base64.RawURLEncoding.EncodeToString(buf)
}

func TestParseKinds(t *testing.T) {
	conf := awgConf()
	cases := []struct {
		name, text, kind string
	}{
		{"awg-conf", conf, "amneziawg"},
		{"amnezia-key", amneziaKey(t, conf), "amneziawg"},
		{"wg-conf", "[Interface]\nPrivateKey = " + key() + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + key() +
			"\nEndpoint = 203.0.113.1:51820\nAllowedIPs = 10.0.0.0/24\n", "wireguard"},
		{"vless-reality", "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?type=tcp&security=reality" +
			"&pbk=" + base64.RawURLEncoding.EncodeToString(make([]byte, 32)) + "&sid=ab&sni=example.com&fp=chrome#Home", "vless-reality"},
	}
	for _, c := range cases {
		js, err := ParseProfile(c.text, c.name)
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		var p Profile
		_ = json.Unmarshal([]byte(js), &p)
		if p.Kind != c.kind {
			t.Fatalf("%s: kind %q, want %q", c.name, p.Kind, c.kind)
		}
	}
	if _, err := ParseProfile("vpn://AAAA", ""); err == nil {
		t.Fatal("пустой ключ vpn:// должен давать ошибку")
	}
}

func TestSplitRoutes(t *testing.T) {
	js, err := ParseProfile("[Interface]\nPrivateKey = "+key()+"\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = "+key()+
		"\nEndpoint = 203.0.113.1:51820\nAllowedIPs = 10.0.0.0/24, 192.168.1.0/24\n", "split")
	if err != nil {
		t.Fatal(err)
	}
	var p Profile
	_ = json.Unmarshal([]byte(js), &p)
	if p.FullTunnel() || len(p.Routes) != 2 {
		t.Fatalf("ожидался частичный туннель, маршруты %v", p.Routes)
	}
}
