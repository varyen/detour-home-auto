package dhcore

import (
	"bytes"
	"compress/zlib"
	"encoding/base64"
	"encoding/json"
	"io"
	"regexp"
	"strings"
)

var (
	reInterface = regexp.MustCompile(`(?i)\[Interface\]`)
	rePeer      = regexp.MustCompile(`(?i)\[Peer\]`)
)

// confFromAmneziaKey: ключ Amnezia «vpn://…» → текст .conf. Формат: base64url от
// qCompress (4 байта длины + zlib) JSON-а, конфиг AWG лежит в нём строкой, иногда
// ещё одним слоем JSON. Старые ключи бывают несжатыми. Повторяет confFromAmneziaKey
// панели Detour (panel/src/components/profiles/awg.ts).
func confFromAmneziaKey(raw string) (string, bool) {
	body := strings.TrimSpace(raw)
	if len(body) < 6 || !strings.EqualFold(body[:6], "vpn://") {
		return "", false
	}
	body = strings.Map(func(r rune) rune {
		if r == ' ' || r == '\n' || r == '\r' || r == '\t' {
			return -1
		}
		return r
	}, body[6:])
	body = strings.TrimRight(body, "=")
	data, err := base64.RawURLEncoding.DecodeString(body)
	if err != nil {
		if data, err = base64.RawStdEncoding.DecodeString(body); err != nil {
			return "", false
		}
	}
	var candidates [][]byte
	if len(data) > 4 {
		if b, err := inflate(data[4:]); err == nil {
			candidates = append(candidates, b)
		}
	}
	if b, err := inflate(data); err == nil {
		candidates = append(candidates, b)
	}
	candidates = append(candidates, data)
	for _, c := range candidates {
		var v any
		if json.Unmarshal(c, &v) != nil {
			continue
		}
		if conf, ok := findConf(v, 0); ok {
			return conf, true
		}
	}
	return "", false
}

func inflate(b []byte) ([]byte, error) {
	r, err := zlib.NewReader(bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	defer r.Close()
	return io.ReadAll(io.LimitReader(r, 1<<20))
}

func findConf(v any, depth int) (string, bool) {
	if depth > 6 {
		return "", false
	}
	switch x := v.(type) {
	case string:
		t := strings.TrimSpace(x)
		if strings.HasPrefix(t, "{") {
			var inner any
			if json.Unmarshal([]byte(t), &inner) == nil {
				return findConf(inner, depth+1)
			}
		}
		if reInterface.MatchString(x) && rePeer.MatchString(x) {
			return x, true
		}
	case []any:
		for _, e := range x {
			if c, ok := findConf(e, depth+1); ok {
				return c, true
			}
		}
	case map[string]any:
		for _, e := range x {
			if c, ok := findConf(e, depth+1); ok {
				return c, true
			}
		}
	}
	return "", false
}
