package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

const testSecret = "test-secret-0123456789abcdef"

// same value as STRIDER for Windows / Android (and Python hmac)
const authVector = "77423a6b9abef2ec86730c19f7d7adc4"

func TestAuthMatchesApps(t *testing.T) {
	g := &guard{authKey: deriveAuthKey(testSecret)}
	if got := g.authTag("0123456789abcdef0123456789abcdef"); got != authVector {
		t.Fatalf("auth tag %s, want %s", got, authVector)
	}
}

func TestGuard(t *testing.T) {
	var hits int
	panel := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		if r.Host == "" || r.Header.Get("X-HWID") == "" {
			t.Errorf("panel got host %q hwid %q", r.Host, r.Header.Get("X-HWID"))
		}
		w.Header().Set("X-Hwid-Active", "true")
		_, _ = w.Write([]byte("dmxlc3M6Ly8uLi4=")) // base64 links
	}))
	defer panel.Close()
	u, _ := url.Parse(panel.URL)
	g := newGuard(u, testSecret)
	front := httptest.NewServer(g)
	defer front.Close()

	hwid := "0123456789abcdef0123456789abcdef"
	do := func(h map[string]string, query string) *http.Response {
		req, _ := http.NewRequest("GET", front.URL+"/sub/abc"+query, nil)
		for k, v := range h {
			req.Header.Set(k, v)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp
	}
	cases := []struct {
		name  string
		h     map[string]string
		query string
		want  int
	}{
		{"browser", map[string]string{"Accept": "text/html,application/xhtml+xml"}, "", 404},
		{"browser with fake hwid", map[string]string{"Accept": "text/html", "X-HWID": hwid, "X-Strider-Auth": authVector}, "", 404},
		{"?html=1", map[string]string{"X-HWID": hwid, "X-Strider-Auth": authVector}, "?html=1", 404},
		{"other app, no hwid", map[string]string{"User-Agent": "v2rayNG/1.9"}, "", 404},
		{"other app with hwid (Happ)", map[string]string{"X-HWID": hwid}, "", 404},
		{"fake auth", map[string]string{"X-HWID": hwid, "X-Strider-Auth": "00000000000000000000000000000000"}, "", 404},
		{"auth of another device", map[string]string{"X-HWID": "another-device-1234", "X-Strider-Auth": authVector}, "", 404},
		{"STRIDER", map[string]string{"X-HWID": hwid, "X-Strider-Auth": authVector, "User-Agent": "v2rayN/6.45"}, "", 200},
	}
	for _, c := range cases {
		before := hits
		resp := do(c.h, c.query)
		if resp.StatusCode != c.want {
			t.Errorf("%s: status %d, want %d", c.name, resp.StatusCode, c.want)
		}
		if c.want == 404 && hits != before {
			t.Errorf("%s: reached the panel", c.name)
		}
		if c.want == 200 && resp.Header.Get("X-Hwid-Active") != "true" {
			t.Errorf("%s: panel headers not passed through", c.name)
		}
	}
}
