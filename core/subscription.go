package dhcore

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/metacubex/mihomo/common/convert"
	"go.yaml.in/yaml/v3"
)

// Subscription — разобранная подписка: заголовок для экрана и профили.
type Subscription struct {
	Title    string     `json:"title"`
	Profiles []*Profile `json:"profiles"`
	// Записи, которые движок не принял (протокол без поддержки и т. п.).
	Skipped int `json:"skipped"`
}

// Подписку Detour (и большинство панелей) просим в формате mihomo: в нём есть
// всё сразу, включая AmneziaWG, которого нет в списке ссылок.
const subUserAgent = "mihomo/1.19 clash.meta (Detour Home Auto)"

// FetchSubscription скачивает подписку и разбирает её. Возвращает JSON Subscription.
func FetchSubscription(rawURL string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return "", errors.New("ссылка подписки — http(s)://…")
	}
	req, _ := http.NewRequest(http.MethodGet, u.String(), nil)
	req.Header.Set("User-Agent", subUserAgent)
	req.Header.Set("Accept", "*/*")
	resp, err := (&http.Client{Timeout: 25 * time.Second}).Do(req)
	if err != nil {
		return "", fmt.Errorf("не удалось скачать подписку: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("сервер подписки ответил %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return "", fmt.Errorf("подписка оборвалась: %v", err)
	}
	s, err := parseSubscription(string(body))
	if err != nil {
		return "", err
	}
	s.Title = subTitle(resp.Header, u)
	b, err := json.Marshal(s)
	return string(b), err
}

// ParseSubscription разбирает уже скачанный текст подписки. Возвращает JSON Subscription.
func ParseSubscription(text string) (string, error) {
	s, err := parseSubscription(text)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(s)
	return string(b), err
}

func parseSubscription(text string) (*Subscription, error) {
	text = strings.TrimPrefix(strings.TrimSpace(text), string(rune(0xFEFF)))
	if text == "" {
		return nil, errors.New("подписка пустая")
	}
	var proxies []map[string]any
	var doc struct {
		Proxies []map[string]any `yaml:"proxies"`
	}
	if strings.Contains(text, "proxies:") && yaml.Unmarshal([]byte(text), &doc) == nil && len(doc.Proxies) > 0 {
		proxies = doc.Proxies
	} else {
		// Список ссылок, в том числе целиком в base64 — ConvertsV2Ray понимает оба.
		ps, err := convert.ConvertsV2Ray([]byte(text))
		if err != nil || len(ps) == 0 {
			return nil, errors.New("в подписке нет ни одного сервера, понятного приложению")
		}
		proxies = ps
	}
	s := &Subscription{}
	for _, px := range proxies {
		p, err := profileFromProxy(normalizeYAML(px).(map[string]any), "")
		if err != nil {
			s.Skipped++
			continue
		}
		s.Profiles = append(s.Profiles, p)
	}
	if len(s.Profiles) == 0 {
		return nil, fmt.Errorf("ни один из серверов подписки (%d) не поддерживается", s.Skipped)
	}
	return s, nil
}

// profileFromProxy: прокси mihomo (из ссылки или из YAML) → Profile.
func profileFromProxy(px map[string]any, fallbackName string) (*Profile, error) {
	name, _ := px["name"].(string)
	if strings.TrimSpace(name) == "" {
		name = fallbackName
	}
	if strings.TrimSpace(name) == "" {
		name = fmt.Sprint(px["type"])
	}
	px["name"] = "proxy"
	kind := fmt.Sprint(px["type"])
	p := &Profile{Name: name, Server: fmt.Sprint(px["server"]), Proxy: px}
	switch kind {
	case "vless":
		network, _ := px["network"].(string)
		tls, _ := px["tls"].(bool)
		switch {
		case px["reality-opts"] != nil:
			kind = "vless-reality"
		case network == "xhttp":
			kind = "vless-xhttp"
		case network == "ws":
			kind = "vless-ws"
		case tls:
			kind = "vless-tls"
		}
	case "wireguard":
		if px["amnezia-wg-option"] != nil {
			kind = "amneziawg"
		}
		allowed := stringList(px["allowed-ips"])
		full := len(allowed) == 0
		for _, a := range allowed {
			if a == "0.0.0.0/0" {
				full = true
			}
		}
		if !full {
			p.Routes = allowed
		}
		p.DNS = stringList(px["dns"])
		if m, ok := toInt(px["mtu"]); ok && m >= 576 {
			p.MTU = m
		}
	}
	p.Kind = kind
	if _, err := buildProxy(p); err != nil {
		return nil, err
	}
	return p, nil
}

// yaml.v3 отдаёт вложенные map[string]any, но числа-ключи и прочее бывает
// map[any]any — json.Marshal такое не ест.
func normalizeYAML(v any) any {
	switch t := v.(type) {
	case map[string]any:
		for k, x := range t {
			t[k] = normalizeYAML(x)
		}
		return t
	case map[any]any:
		m := make(map[string]any, len(t))
		for k, x := range t {
			m[fmt.Sprint(k)] = normalizeYAML(x)
		}
		return m
	case []any:
		for i, x := range t {
			t[i] = normalizeYAML(x)
		}
		return t
	}
	return v
}

func stringList(v any) []string {
	var out []string
	switch t := v.(type) {
	case []string:
		return t
	case []any:
		for _, x := range t {
			if s := strings.TrimSpace(fmt.Sprint(x)); s != "" {
				out = append(out, s)
			}
		}
	case string:
		return splitList(t)
	}
	return out
}

func toInt(v any) (int, bool) {
	switch t := v.(type) {
	case int:
		return t, true
	case int64:
		return int(t), true
	case float64:
		return int(t), true
	case string:
		n, err := strconv.Atoi(t)
		return n, err == nil
	}
	return 0, false
}

// Заголовок подписки: profile-title (в т. ч. «base64:…»), имя файла, иначе хост.
func subTitle(h http.Header, u *url.URL) string {
	if t := strings.TrimSpace(h.Get("Profile-Title")); t != "" {
		if strings.HasPrefix(t, "base64:") {
			if b, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(t, "base64:")); err == nil {
				return strings.TrimSpace(string(b))
			}
		}
		return t
	}
	if _, params, err := mime.ParseMediaType(h.Get("Content-Disposition")); err == nil {
		if f := strings.TrimSpace(params["filename"]); f != "" {
			return strings.TrimSuffix(strings.TrimSuffix(f, ".yaml"), ".txt")
		}
	}
	return u.Hostname()
}
