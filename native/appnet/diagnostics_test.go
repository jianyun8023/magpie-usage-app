package appnet

import (
	"net/netip"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/views"
)

func TestSubnetRoutesKeepsAdvertisedHostRoute(t *testing.T) {
	peerIP := mustAddr("100.77.66.111")
	routes := views.SliceOf([]netip.Prefix{
		mustPrefix("100.77.66.111/32"),
		mustPrefix("192.168.2.1/32"),
		mustPrefix("192.168.2.0/24"),
		mustPrefix("fd7a:115c:a1e0::1/128"),
	})

	got := subnetRoutes(&routes, []netip.Addr{peerIP})

	want := []string{"192.168.2.1/32", "192.168.2.0/24"}
	if len(got) != len(want) {
		t.Fatalf("route count = %d, want %d (%v)", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("route[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestSubnetRoutesNilAndEmpty(t *testing.T) {
	if got := subnetRoutes(nil, nil); len(got) != 0 {
		t.Fatalf("nil routes = %v, want empty", got)
	}
	empty := views.SliceOf([]netip.Prefix{})
	if got := subnetRoutes(&empty, nil); len(got) != 0 {
		t.Fatalf("empty routes = %v, want empty", got)
	}
}

func TestDiagnosticsPeerJSONFiltersRoutes(t *testing.T) {
	peerIP := mustAddr("100.77.66.111")
	allowed := views.SliceOf([]netip.Prefix{mustPrefix("100.77.66.111/32"), mustPrefix("192.168.2.0/24")})
	primary := views.SliceOf([]netip.Prefix{mustPrefix("192.168.2.0/24")})
	handshake := time.Date(2026, 10, 25, 8, 30, 15, 123456789, time.FixedZone("UTC+8", 8*60*60))
	peer := &ipnstate.PeerStatus{
		TailscaleIPs:  []netip.Addr{peerIP},
		AllowedIPs:    &allowed,
		PrimaryRoutes: &primary,
		Online:        true,
		Active:        true,
		RxBytes:       123,
		TxBytes:       456,
		LastHandshake: handshake,
	}

	got := newDiagnosticsPeerJSON(peer)

	if len(got.TailscaleIPs) != 1 || got.TailscaleIPs[0] != "100.77.66.111" {
		t.Fatalf("tailscale IPs = %v", got.TailscaleIPs)
	}
	if len(got.AllowedIPs) != 1 || got.AllowedIPs[0] != "192.168.2.0/24" {
		t.Fatalf("allowed IPs = %v", got.AllowedIPs)
	}
	if len(got.PrimaryRoutes) != 1 || got.PrimaryRoutes[0] != "192.168.2.0/24" {
		t.Fatalf("primary routes = %v", got.PrimaryRoutes)
	}
	if !got.Online || !got.Active || got.RxBytes != 123 || got.TxBytes != 456 {
		t.Fatalf("unexpected peer counters/state: %+v", got)
	}
	if got.LastHandshake != "2026-10-25T00:30:15.123456789Z" {
		t.Fatalf("last handshake = %q", got.LastHandshake)
	}
}

func mustAddr(value string) netip.Addr {
	addr, err := netip.ParseAddr(value)
	if err != nil {
		panic(err)
	}
	return addr
}

func mustPrefix(value string) netip.Prefix {
	prefix, err := netip.ParsePrefix(value)
	if err != nil {
		panic(err)
	}
	return prefix
}
