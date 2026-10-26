// dhprobe — проверка разбора и связности профиля с ПК: dhprobe <файл|ссылка|подписка http(s)://…>…
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	dhcore "github.com/varyen/detour-home-auto/core"
)

func main() {
	for _, arg := range os.Args[1:] {
		if strings.HasPrefix(arg, "http://") || strings.HasPrefix(arg, "https://") {
			js, err := dhcore.FetchSubscription(arg)
			if err != nil {
				fmt.Printf("%s: подписка: %v\n", short(arg), err)
				continue
			}
			var s dhcore.Subscription
			_ = json.Unmarshal([]byte(js), &s)
			fmt.Printf("подписка %q: профилей %d, пропущено %d\n", s.Title, len(s.Profiles), s.Skipped)
			for _, p := range s.Profiles {
				b, _ := json.Marshal(p)
				probe(string(b), "  "+p.Name, p)
			}
			continue
		}
		text, name := arg, ""
		if b, err := os.ReadFile(arg); err == nil {
			text, name = string(b), strings.TrimSuffix(filepath.Base(arg), filepath.Ext(arg))
		}
		js, err := dhcore.ParseProfile(text, name)
		if err != nil {
			fmt.Printf("%s: разбор: %v\n", short(arg), err)
			continue
		}
		var p dhcore.Profile
		_ = json.Unmarshal([]byte(js), &p)
		probe(js, short(arg), &p)
	}
}

func probe(js, label string, p *dhcore.Profile) {
	url := os.Getenv("DHA_PROBE_URL")
	ms, err := dhcore.Probe(js, url, 10000)
	fmt.Printf("%s: %s %q сервер=%s маршруты=%v → %d мс err=%v\n", label, p.Kind, p.Name, p.Server, p.Routes, ms, err)
}

func short(s string) string {
	if len(s) > 40 {
		return s[:40] + "…"
	}
	return s
}
