package appnet

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestHTTPGetRejectsCrossSchemeRedirect(t *testing.T) {
	var startSeen atomic.Bool
	var downgradeHit atomic.Bool
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/downgrade" {
			downgradeHit.Store(true)
			t.Errorf("downgrade target must not be reached")
			return
		}
		startSeen.Store(true)
		http.Redirect(w, r, "http://"+r.Host+"/downgrade", http.StatusFound)
	}))
	defer server.Close()

	client := testClientWithDial(t, (&net.Dialer{}).DialContext)
	client.mu.Lock()
	client.active.transport.TLSClientConfig = server.Client().Transport.(*http.Transport).TLSClientConfig
	client.mu.Unlock()
	resp, err := client.HTTPGet(server.URL+"/start", "cookie=secret")
	if err == nil || resp != nil {
		t.Fatalf("expected redirect rejection, got response=%v err=%v", resp, err)
	}
	if !startSeen.Load() {
		t.Fatal("original HTTPS request was not reached")
	}
	if downgradeHit.Load() {
		t.Fatal("downgrade target was reached")
	}
}

func TestHTTPGetAllowsSameOriginRedirect(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/start" {
			http.Redirect(w, r, "/final", http.StatusFound)
			return
		}
		if r.Header.Get("Cookie") != "cookie=secret" {
			t.Errorf("cookie not preserved")
		}
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer server.Close()

	client := testClientWithDial(t, (&net.Dialer{}).DialContext)
	resp, err := client.HTTPGet(server.URL+"/start", "cookie=secret")
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 || resp.Body != `{"ok":true}` {
		t.Fatalf("unexpected response: %+v", resp)
	}
}

func TestHTTPGetFailsClosedWhenNoSession(t *testing.T) {
	client, err := NewClient(t.TempDir(), memoryStore{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.HTTPGet("http://192.0.2.1/", ""); err == nil {
		t.Fatal("expected fail-closed error")
	}
}

func TestStopCancelsInFlightRequest(t *testing.T) {
	release := make(chan struct{})
	entered := make(chan struct{})
	client := testClientWithDial(t, func(ctx context.Context, network, addr string) (net.Conn, error) {
		entered <- struct{}{}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-release:
			return nil, net.ErrClosed
		}
	})

	done := make(chan error, 1)
	go func() {
		_, err := client.HTTPGet("http://192.0.2.1/", "")
		done <- err
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("request did not enter dial")
	}
	if err := client.StopWireGuard(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("expected cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal("request was not canceled promptly")
	}
	close(release)
}

func TestOldSessionRequestCannotUseNewSession(t *testing.T) {
	client, err := NewClient(t.TempDir(), memoryStore{})
	if err != nil {
		t.Fatal(err)
	}
	old := newSession("wireguard", func(context.Context, string, string) (net.Conn, error) {
		panic("old session must not be dialed")
		return nil, net.ErrClosed
	}, nil)
	if err := client.switchSession(old); err != nil {
		t.Fatal(err)
	}
	oldID := client.SessionID()
	next := newSession("wireguard", func(context.Context, string, string) (net.Conn, error) {
		panic("new session must not be dialed")
	}, nil)
	if err := client.switchSession(next); err != nil {
		t.Fatal(err)
	}
	if _, err := client.HTTPGetForSession("http://192.0.2.1/", "", oldID); err == nil {
		t.Fatal("expected stale session rejection")
	}
}

func testClientWithDial(t *testing.T, dial func(context.Context, string, string) (net.Conn, error)) *Client {
	client, err := NewClient(t.TempDir(), memoryStore{})
	if err != nil {
		panic(err)
	}
	session := newSession("wireguard", dial, nil)
	if err := client.switchSession(session); err != nil {
		panic(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	return client
}

type memoryStore struct{}

func (memoryStore) Read(string) (string, error) { return "", nil }
func (memoryStore) Write(string, string) error  { return nil }
