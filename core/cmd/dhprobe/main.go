// dhprobe — проверка разбора и связности профиля с ПК: dhprobe <файл|ссылка>…
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
		ms, err := dhcore.Probe(js, "", 10000)
		fmt.Printf("%s: %s %q сервер=%s маршруты=%v → %d мс err=%v\n", short(arg), p.Kind, p.Name, p.Server, p.Routes, ms, err)
	}
}

func short(s string) string {
	if len(s) > 40 {
		return s[:40] + "…"
	}
	return s
}
