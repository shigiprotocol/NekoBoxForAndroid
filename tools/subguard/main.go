// STRIDER sub guard: a small reverse proxy in front of the 3x-ui
// subscription port.
//
// It lets a subscription request through only when it comes from STRIDER:
//   - the browser page of 3x-ui (which lists every config and skips the
//     panel's HWID check) is blocked;
//   - X-HWID is required (so the panel's HWID device limit always applies);
//   - X-Strider-Auth must match the HWID (HMAC with the STRIDER lock key),
//     so other apps, curl or a fake X-HWID get nothing.
//
// Everything else is passed to 3x-ui unchanged, including its HWID answers.
package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"flag"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"time"
)

type guard struct {
	authKey []byte // nil: no STRIDER check (HWID + browser block only)
	proxy   http.Handler
}

func deriveAuthKey(secret string) []byte {
	sum := sha256.Sum256([]byte("strider-auth-v1|" + strings.TrimSpace(secret)))
	return sum[:]
}

func (g *guard) authTag(hwid string) string {
	m := hmac.New(sha256.New, g.authKey)
	m.Write([]byte(hwid))
	return hex.EncodeToString(m.Sum(nil))[:32]
}

// allowed returns "" when the request may go to the panel, else the reason.
func (g *guard) allowed(r *http.Request) string {
	q := r.URL.Query()
	if strings.Contains(strings.ToLower(r.Header.Get("Accept")), "text/html") ||
		q.Get("html") == "1" || strings.EqualFold(q.Get("view"), "html") {
		return "browser page"
	}
	hwid := strings.TrimSpace(r.Header.Get("X-HWID"))
	if len(hwid) < 6 || len(hwid) > 128 {
		return "no HWID"
	}
	if g.authKey != nil {
		got := strings.TrimSpace(r.Header.Get("X-Strider-Auth"))
		if !hmac.Equal([]byte(got), []byte(g.authTag(hwid))) {
			return "not STRIDER"
		}
	}
	return ""
}

func (g *guard) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if reason := g.allowed(r); reason != "" {
		log.Printf("blocked %s %s: %s", clientIP(r), r.URL.Path, reason)
		http.NotFound(w, r)
		return
	}
	g.proxy.ServeHTTP(w, r)
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func newGuard(upstream *url.URL, secret string) *guard {
	rp := httputil.NewSingleHostReverseProxy(upstream)
	rp.Transport = &http.Transport{
		// the panel runs on this server (127.0.0.1); its certificate is for the public name
		TLSClientConfig:       &tls.Config{InsecureSkipVerify: true}, //nolint:gosec // loopback only
		ResponseHeaderTimeout: 30 * time.Second,
		IdleConnTimeout:       60 * time.Second,
	}
	orig := rp.Director
	rp.Director = func(r *http.Request) {
		host := r.Host
		orig(r)
		r.Host = host // keep the public host: 3x-ui builds links from it
		if ip := clientIP(r); ip != "" {
			r.Header.Set("X-Real-IP", ip)
		}
	}
	g := &guard{proxy: rp}
	if len(strings.TrimSpace(secret)) >= 16 {
		g.authKey = deriveAuthKey(secret)
	}
	return g
}

func env(name, def string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return def
}

func main() {
	listen := flag.String("listen", env("LISTEN", ":2096"), "public address (the subscription port users already use)")
	upstreamURL := flag.String("upstream", env("UPSTREAM", "http://127.0.0.1:2097"), "3x-ui subscription server (moved to localhost)")
	cert := flag.String("cert", env("CERT", ""), "TLS certificate (fullchain) for the public port; empty = plain HTTP")
	key := flag.String("key", env("KEY", ""), "TLS private key")
	flag.Parse()
	secret := os.Getenv("STRIDER_LOCK_KEY")

	u, err := url.Parse(*upstreamURL)
	if err != nil || u.Host == "" {
		log.Fatalf("bad upstream %q", *upstreamURL)
	}
	g := newGuard(u, secret)
	if g.authKey == nil {
		log.Printf("warning: STRIDER_LOCK_KEY not set: only HWID is required, other apps that send an HWID can still download")
	}
	srv := &http.Server{
		Addr:              *listen,
		Handler:           g,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
	log.Printf("STRIDER sub guard: %s -> %s (TLS: %v)", *listen, u, *cert != "")
	if *cert != "" {
		err = srv.ListenAndServeTLS(*cert, *key)
	} else {
		err = srv.ListenAndServe()
	}
	log.Fatal(err)
}
