// Package dhcore — движок Detour Home Auto поверх mihomo.
// Собирается gomobile: на Android — .aar, на iOS — .xcframework.
//
// Модель: платформа поднимает туннель сама (VpnService / NEPacketTunnelProvider),
// исключает из него свой процесс и отдаёт сюда fd. mihomo читает пакеты из fd и
// отправляет всё в единственный прокси профиля. Свои сокеты mihomo из туннеля
// исключены платформой, поэтому protect() не нужен.
package dhcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener"
	"github.com/metacubex/mihomo/log"
)

// Адрес интерфейса туннеля: mihomo берёт его из fake-ip-range (/30), платформа
// должна назначить интерфейсу тот же.
const (
	TunAddress4 = "198.18.0.1"
	TunPrefix4  = 30
	TunDNS4     = "198.18.0.2"
	TunAddress6 = "fdfe:dcba:9876::1"
	TunPrefix6  = 126
	DefaultMTU  = 1400
)

var (
	mu      sync.Mutex
	running bool
	logMu   sync.Mutex
	logRing []string
	logOnce sync.Once
)

func startLog() {
	logOnce.Do(func() {
		sub := log.Subscribe()
		go func() {
			for ev := range sub {
				line := time.Now().Format("15:04:05") + " " + ev.Type() + " " + ev.Payload
				logMu.Lock()
				logRing = append(logRing, line)
				if len(logRing) > 200 {
					logRing = logRing[len(logRing)-200:]
				}
				logMu.Unlock()
			}
		}()
	})
}

func lastError() string {
	logMu.Lock()
	defer logMu.Unlock()
	for i := len(logRing) - 1; i >= 0; i-- {
		if strings.Contains(logRing[i], " error ") || strings.Contains(logRing[i], " fatal ") {
			return logRing[i]
		}
	}
	return "подробности в журнале"
}

// RecentLog — последние строки журнала mihomo (для экрана диагностики).
func RecentLog() string {
	logMu.Lock()
	defer logMu.Unlock()
	return strings.Join(logRing, "\n")
}

func Version() string { return C.Version }

func decode(profileJSON string) (*Profile, error) {
	var p Profile
	if err := json.Unmarshal([]byte(profileJSON), &p); err != nil {
		return nil, fmt.Errorf("профиль повреждён: %w", err)
	}
	if p.Proxy == nil {
		return nil, errors.New("в профиле нет прокси")
	}
	p.Proxy["name"] = "proxy"
	return &p, nil
}

func buildProxy(p *Profile) (C.Proxy, error) {
	px, err := adapter.ParseProxy(p.Proxy)
	if err != nil {
		return nil, fmt.Errorf("профиль не принят движком: %w", err)
	}
	return px, nil
}

func buildConfig(p *Profile, fd int32) map[string]any {
	mtu := p.MTU
	if mtu == 0 {
		mtu = DefaultMTU
	}
	full := p.FullTunnel()
	dns := map[string]any{
		"enable":             true,
		"ipv6":               false,
		"default-nameserver": []string{"1.1.1.1", "8.8.8.8"},
		"nameserver":         []string{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"},
		"fake-ip-range":      TunAddress4 + "/16",
	}
	if full {
		// Имена резолвит сервер на той стороне: в туннель уходит домен, а не IP.
		dns["enhanced-mode"] = "fake-ip"
	} else {
		dns["enhanced-mode"] = "normal"
	}
	tun := map[string]any{
		"enable":                true,
		"stack":                 "gvisor",
		"file-descriptor":       fd,
		"auto-route":            false,
		"auto-detect-interface": false,
		"mtu":                   mtu,
		"inet6-address":         []string{fmt.Sprintf("%s/%d", TunAddress6, TunPrefix6)},
	}
	if full {
		tun["dns-hijack"] = []string{"any:53", "tcp://any:53"}
	} else {
		tun["dns-hijack"] = []string{}
	}
	return map[string]any{
		"mode":                "rule",
		"log-level":           "info",
		"ipv6":                false,
		"allow-lan":           false,
		"unified-delay":       true,
		"tcp-concurrent":      true,
		"find-process-mode":   "off",
		"geodata-mode":        false,
		"geo-auto-update":     false,
		"global-client-fingerprint": "chrome",
		"profile": map[string]any{
			"store-selected": false,
			"store-fake-ip":  false,
		},
		"dns":     dns,
		"tun":     tun,
		"proxies": []any{p.Proxy},
		"rules":   []string{"MATCH,proxy"},
	}
}

// Start поднимает mihomo на fd туннеля. home — каталог для служебных файлов.
// Повторный вызов перенастраивает работающий движок (смена профиля, новый fd).
func Start(profileJSON string, fd int32, home string) error {
	p, err := decode(profileJSON)
	if err != nil {
		return err
	}
	if _, err := buildProxy(p); err != nil {
		return err
	}
	startLog()
	mu.Lock()
	defer mu.Unlock()
	if home != "" {
		_ = os.MkdirAll(home, 0o755)
		C.SetHomeDir(home)
	}
	// iOS отдаёт расширению туннеля ~50 МБ — держим кучу заметно ниже.
	debug.SetMemoryLimit(32 << 20)
	raw, _ := json.Marshal(buildConfig(p, fd))
	cfg, err := executor.ParseWithBytes(raw)
	if err != nil {
		return fmt.Errorf("конфиг движка: %w", err)
	}
	executor.ApplyConfig(cfg, true)
	// Ошибку создания tun ApplyConfig только пишет в журнал — проверяем сами.
	if tc := listener.GetTunConf(); !tc.Enable || tc.FileDescriptor != int(fd) {
		executor.Shutdown()
		running = false
		return fmt.Errorf("движок не принял интерфейс туннеля: %s", lastError())
	}
	running = true
	log.Infoln("[DHA] запущен профиль %s (%s)", p.Name, p.Kind)
	return nil
}

// Stop останавливает туннель и закрывает fd.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if !running {
		return
	}
	executor.Shutdown()
	running = false
	debug.FreeOSMemory()
}

func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// Probe проверяет профиль без туннеля: запрос через прокси, ответ — задержка в мс.
// Вызывать вне туннеля (или из исключённого из него процесса).
func Probe(profileJSON, url string, timeoutMs int32) (int32, error) {
	p, err := decode(profileJSON)
	if err != nil {
		return 0, err
	}
	px, err := buildProxy(p)
	if err != nil {
		return 0, err
	}
	defer px.Close()
	if url == "" {
		url = "https://www.gstatic.com/generate_204"
	}
	if timeoutMs <= 0 {
		timeoutMs = 8000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	ms, err := px.URLTest(ctx, url, nil)
	if err != nil {
		return 0, err
	}
	return int32(ms), nil
}
