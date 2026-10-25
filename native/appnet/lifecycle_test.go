package appnet

import (
	"context"
	"net"
	"testing"
)

func TestInvalidWireGuardParametersStopOldModes(t *testing.T) {
	cases := []struct {
		name   string
		uapi   string
		locals string
	}{
		{name: "invalid-json", uapi: "private_key=invalid", locals: `invalid-json`},
		{name: "empty-locals", uapi: "private_key=invalid", locals: `[]`},
		{name: "invalid-ipc", uapi: "private_key=invalid", locals: `["192.0.2.1"]`},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			old := newSession("tailscale", func(context.Context, string, string) (net.Conn, error) {
				t.Error("old Tailscale session must not dial")
				return nil, net.ErrClosed
			}, nil)
			client, err := NewClient(t.TempDir(), memoryStore{})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = client.Close() })
			if err := client.switchSession(old); err != nil {
				t.Fatal(err)
			}
			oldID := client.SessionID()

			if err := client.StartWireGuard(test.uapi, test.locals, `[]`, 1280); err == nil {
				t.Fatal("expected invalid WireGuard parameters")
			}
			if client.SessionID() == oldID {
				t.Fatal("old session remained active")
			}
			if _, err := client.HTTPGetForSession("http://192.0.2.1/", "", oldID); err == nil {
				t.Fatal("old session request unexpectedly succeeded")
			}
		})
	}
}

func TestInvalidWireGuardIpcStopsOldSession(t *testing.T) {
	client := testClientWithDial(t, (&net.Dialer{}).DialContext)
	oldID := client.SessionID()

	if err := client.StartWireGuard("private_key=invalid", `["192.0.2.1"]`, `[]`, 1280); err == nil {
		t.Fatal("expected invalid WireGuard configuration")
	}
	if client.SessionID() == oldID {
		t.Fatal("old WireGuard session remained active")
	}
	if _, err := client.HTTPGetForSession("http://192.0.2.1/", "", oldID); err == nil {
		t.Fatal("old session request unexpectedly succeeded")
	}
}

func TestInvalidWireGuardParametersWithoutActiveSessionStayClosed(t *testing.T) {
	client, err := NewClient(t.TempDir(), memoryStore{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	if err := client.StartWireGuard("private_key=invalid", `[]`, `[]`, 1280); err == nil {
		t.Fatal("expected invalid WireGuard parameters")
	}
	if _, err := client.HTTPGet("http://192.0.2.1/", ""); err == nil {
		t.Fatal("expected fail-closed request without session")
	}
}
