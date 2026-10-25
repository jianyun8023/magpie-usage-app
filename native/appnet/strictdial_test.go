package appnet

import (
	"context"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"

	"tailscale.com/net/tsdial"
)

func TestStrictUserDialUsesOnlyNetstackForIP(t *testing.T) {
	var dialed bool
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	dialer := &tsdial.Dialer{
		NetstackDialTCP: func(ctx context.Context, addr netip.AddrPort) (net.Conn, error) {
			dialed = true
			if addr.String() != "127.0.0.1:80" {
				t.Errorf("unexpected dialed address %s", addr)
			}
			return client, nil
		},
		NetstackDialUDP: func(context.Context, netip.AddrPort) (net.Conn, error) {
			t.Fatal("UDP must not be used for tcp dial")
			return nil, net.ErrClosed
		},
	}

	conn, err := dialer.StrictUserDial(context.Background(), "tcp", "127.0.0.1:80")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if !dialed {
		t.Fatal("netstack TCP fake was not called")
	}
}

func TestStrictUserDialRejectsUnknownHostWithoutSystemDNS(t *testing.T) {
	var systemDNS atomic.Bool
	original := net.DefaultResolver
	t.Cleanup(func() { net.DefaultResolver = original })
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(context.Context, string, string) (net.Conn, error) {
			systemDNS.Store(true)
			return nil, net.ErrClosed
		},
	}

	dialer := &tsdial.Dialer{
		NetstackDialTCP: func(context.Context, netip.AddrPort) (net.Conn, error) {
			t.Fatal("unknown host must not reach netstack")
			return nil, net.ErrClosed
		},
		NetstackDialUDP: func(context.Context, netip.AddrPort) (net.Conn, error) {
			t.Fatal("unknown host must not reach netstack")
			return nil, net.ErrClosed
		},
	}

	if _, err := dialer.StrictUserDial(context.Background(), "tcp", "unknown-host.invalid:80"); err == nil {
		t.Fatal("expected unknown host rejection")
	}
	if systemDNS.Load() {
		t.Fatal("system resolver was used")
	}
}
