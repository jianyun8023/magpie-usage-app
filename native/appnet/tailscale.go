package appnet

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"strings"

	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

func (c *Client) StartTailscale(controlURL string) error {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	controlURL = strings.TrimSpace(controlURL)
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return errors.New("client is closed")
	}
	active := c.active
	c.mu.Unlock()
	if active != nil {
		if err := c.switchSession(nil); err != nil {
			return publicError(err)
		}
	}

	server := &tsnet.Server{
		Dir:        c.stateDir,
		Store:      stateStoreAdapter{store: c.store},
		Hostname:   "x2-app",
		ControlURL: controlURL,
		UserLogf:   func(string, ...any) {},
		Logf:       func(string, ...any) {},
	}
	if err := server.Start(); err != nil {
		server.Close()
		return publicError(err)
	}
	lc, err := server.LocalClient()
	if err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
		_, err = lc.EditPrefs(ctx, &ipn.MaskedPrefs{
			Prefs:       ipn.Prefs{RouteAll: true},
			RouteAllSet: true,
		})
		cancel()
	}
	if err != nil {
		server.Close()
		return publicError(err)
	}
	s := newSession("tailscale", func(ctx context.Context, network, address string) (net.Conn, error) {
		return server.DialStrict(ctx, network, address)
	}, server.Close)
	s.server = server
	return c.switchSession(s)
}

type statusJSON struct {
	BackendState string   `json:"backendState"`
	AuthURL      string   `json:"authUrl"`
	Addresses    []string `json:"addresses"`
	Health       []string `json:"health"`
	Connected    bool     `json:"connected"`
}

func marshalStatus(out statusJSON) (string, error) {
	b, err := json.Marshal(out)
	if err != nil {
		return "", publicError(err)
	}
	return string(b), nil
}

func (c *Client) StopTailscale() error {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	return c.stopActive("tailscale")
}

func (c *Client) stopActive(kind string) error {
	c.mu.Lock()
	active := c.active
	c.mu.Unlock()
	if active == nil || active.kind != kind {
		return nil
	}
	return c.switchSession(nil)
}

func (c *Client) LoginTailscale() error {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	c.mu.Lock()
	active := c.active
	c.mu.Unlock()
	if active == nil || active.kind != "tailscale" {
		return errors.New("Tailscale is not active")
	}
	lc, err := active.server.LocalClient()
	if err != nil {
		return publicError(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	if err := lc.StartLoginInteractive(ctx); err != nil {
		return publicError(err)
	}
	return nil
}

func (c *Client) LogoutTailscale(controlURL string) error {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	c.mu.Lock()
	closed := c.closed
	c.mu.Unlock()
	if closed {
		return errors.New("client is closed")
	}
	c.mu.Lock()
	active := c.active
	c.mu.Unlock()
	if active != nil && active.kind == "tailscale" {
		if err := localLogout(active.server); err != nil {
			return err
		}
		return c.switchSession(nil)
	}
	controlURL = strings.TrimSpace(controlURL)
	server := &tsnet.Server{
		Dir:        c.stateDir,
		Store:      stateStoreAdapter{store: c.store},
		Hostname:   "x2-app",
		ControlURL: controlURL,
		UserLogf:   func(string, ...any) {},
		Logf:       func(string, ...any) {},
	}
	if err := server.Start(); err != nil {
		server.Close()
		return publicError(err)
	}
	defer server.Close()
	return localLogout(server)
}

func localLogout(server *tsnet.Server) error {
	lc, err := server.LocalClient()
	if err != nil {
		return publicError(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	if err := lc.Logout(ctx); err != nil {
		return publicError(err)
	}
	return nil
}

func (c *Client) TailscaleStatusJSON() (string, error) {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	c.mu.Lock()
	active := c.active
	c.mu.Unlock()
	if active == nil || active.kind != "tailscale" {
		return `{"backendState":"Stopped","authUrl":"","addresses":[],"health":[],"connected":false}`, nil
	}
	lc, err := active.server.LocalClient()
	if err != nil {
		return "", publicError(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	status, err := lc.Status(ctx)
	if err != nil {
		return "", publicError(err)
	}
	addresses := make([]string, 0, len(status.TailscaleIPs))
	for _, addr := range status.TailscaleIPs {
		addresses = append(addresses, addr.String())
	}
	out := statusJSON{
		BackendState: status.BackendState,
		AuthURL:      status.AuthURL,
		Addresses:    addresses,
		Health:       status.Health,
		Connected:    status.BackendState == ipn.Running.String(),
	}
	if out.Health == nil {
		out.Health = []string{}
	}
	if out.Addresses == nil {
		out.Addresses = []string{}
	}
	return marshalStatus(out)
}
