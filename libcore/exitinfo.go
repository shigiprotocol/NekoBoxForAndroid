package libcore

// ShigiProtocol: query the public exit IP and country of the active outbound.

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"libcore/device"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
)

type exitInfoSource struct {
	url   string
	parse func(body []byte) (ip string, cc string)
}

func parseCloudflareTrace(body []byte) (ip string, cc string) {
	for _, line := range strings.Split(string(body), "\n") {
		line = strings.TrimSpace(line)
		if v, ok := strings.CutPrefix(line, "ip="); ok {
			ip = v
		} else if v, ok := strings.CutPrefix(line, "loc="); ok {
			cc = v
		}
	}
	return
}

var exitInfoSources = []exitInfoSource{
	{"https://1.1.1.1/cdn-cgi/trace", parseCloudflareTrace},
	{"https://www.cloudflare.com/cdn-cgi/trace", parseCloudflareTrace},
	{"https://api.ip.sb/geoip", func(body []byte) (string, string) {
		var r struct {
			IP          string `json:"ip"`
			CountryCode string `json:"country_code"`
		}
		_ = json.Unmarshal(body, &r)
		return r.IP, r.CountryCode
	}},
	{"http://ip-api.com/json/?fields=query,countryCode", func(body []byte) (string, string) {
		var r struct {
			Query       string `json:"query"`
			CountryCode string `json:"countryCode"`
		}
		_ = json.Unmarshal(body, &r)
		return r.Query, r.CountryCode
	}},
}

// GetExitInfo returns "IP|CC" (CC may be empty) as seen by public services
// when connecting through the given box instance (or the main instance if nil).
func GetExitInfo(i *BoxInstance, timeout int32) (result string, err error) {
	defer device.DeferPanicToError("GetExitInfo", func(err_ error) { err = err_ })

	if i == nil {
		i = mainInstance
	}
	if i == nil {
		return "", errors.New("core not started")
	}
	var tracker adapter.ConnectionTracker
	if i.v2api != nil {
		tracker = i.v2api.StatsService()
	}
	client := boxapi.CreateProxyHttpClient(i.Box, tracker)
	defer client.CloseIdleConnections()

	if timeout <= 0 {
		timeout = 5000
	}

	var lastErr error = errors.New("no source available")
	for _, src := range exitInfoSources {
		ip, cc, e := fetchExitInfo(client, src, time.Duration(timeout)*time.Millisecond)
		if e != nil {
			lastErr = e
			continue
		}
		if ip != "" {
			return ip + "|" + strings.ToUpper(strings.TrimSpace(cc)), nil
		}
	}
	return "", lastErr
}

func fetchExitInfo(client *http.Client, src exitInfoSource, timeout time.Duration) (string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, src.url, nil)
	if err != nil {
		return "", "", err
	}
	req.Header.Set("User-Agent", "curl/8.5.0")
	resp, err := client.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", errors.New(resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return "", "", err
	}
	ip, cc := src.parse(body)
	return strings.TrimSpace(ip), cc, nil
}
