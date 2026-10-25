package appnet

import (
	"context"
	"encoding/json"
	"net/netip"
	"sort"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/tsaddr"
	"tailscale.com/types/views"
)

type tailscaleDiagnosticsJSON struct {
	AcceptRoutes bool                  `json:"acceptRoutes"`
	BackendState string                `json:"backendState"`
	Addresses    []string              `json:"addresses"`
	PeerCount    int                   `json:"peerCount"`
	Peers        []diagnosticsPeerJSON `json:"peers"`
}

type diagnosticsPeerJSON struct {
	TailscaleIPs  []string `json:"tailscaleIPs"`
	AllowedIPs    []string `json:"allowedIPs"`
	PrimaryRoutes []string `json:"primaryRoutes"`
	Online        bool     `json:"online"`
	Active        bool     `json:"active"`
	RxBytes       int64    `json:"rxBytes"`
	TxBytes       int64    `json:"txBytes"`
	LastHandshake string   `json:"lastHandshake"`
}

func (c *Client) TailscaleDiagnosticsJSON() (string, error) {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()

	c.mu.Lock()
	active := c.active
	c.mu.Unlock()
	if active == nil || active.kind != "tailscale" {
		return `{"acceptRoutes":false,"backendState":"Stopped","addresses":[],"peerCount":0,"peers":[]}`, nil
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
	prefs, err := lc.GetPrefs(ctx)
	if err != nil {
		return "", publicError(err)
	}

	addresses := make([]string, 0, len(status.TailscaleIPs))
	for _, address := range status.TailscaleIPs {
		addresses = append(addresses, address.String())
	}
	peers := make([]diagnosticsPeerJSON, 0, len(status.Peer))
	for _, peer := range status.Peer {
		peerJSON := newDiagnosticsPeerJSON(peer)
		if len(peerJSON.AllowedIPs) > 0 || len(peerJSON.PrimaryRoutes) > 0 {
			peers = append(peers, peerJSON)
		}
	}
	sort.SliceStable(peers, func(i, j int) bool {
		return diagnosticsPeerKey(peers[i]) < diagnosticsPeerKey(peers[j])
	})
	out := tailscaleDiagnosticsJSON{
		AcceptRoutes: prefs.RouteAll,
		BackendState: status.BackendState,
		Addresses:    addresses,
		PeerCount:    len(status.Peer),
		Peers:        peers,
	}
	encoded, err := json.Marshal(out)
	if err != nil {
		return "", publicError(err)
	}
	return string(encoded), nil
}

func newDiagnosticsPeerJSON(status *ipnstate.PeerStatus) diagnosticsPeerJSON {
	lastHandshake := ""
	if !status.LastHandshake.IsZero() {
		lastHandshake = status.LastHandshake.UTC().Format(time.RFC3339Nano)
	}
	return diagnosticsPeerJSON{
		TailscaleIPs:  addressStrings(status.TailscaleIPs),
		AllowedIPs:    subnetRoutes(status.AllowedIPs, status.TailscaleIPs),
		PrimaryRoutes: subnetRoutes(status.PrimaryRoutes, status.TailscaleIPs),
		Online:        status.Online,
		Active:        status.Active,
		RxBytes:       status.RxBytes,
		TxBytes:       status.TxBytes,
		LastHandshake: lastHandshake,
	}
}

func diagnosticsPeerKey(peer diagnosticsPeerJSON) string {
	if len(peer.TailscaleIPs) > 0 {
		return peer.TailscaleIPs[0]
	}
	if len(peer.AllowedIPs) > 0 {
		return peer.AllowedIPs[0]
	}
	return peer.PrimaryRoutes[0]
}

func subnetRoutes(routes *views.Slice[netip.Prefix], peerIPs []netip.Addr) []string {
	if routes == nil || routes.Len() == 0 {
		return []string{}
	}
	peerIPSet := make(map[netip.Addr]bool, len(peerIPs))
	for _, ip := range peerIPs {
		peerIPSet[ip] = true
	}
	result := make([]string, 0, routes.Len())
	for _, route := range routes.All() {
		if route.IsSingleIP() && (peerIPSet[route.Addr()] || tsaddr.IsTailscaleIP(route.Addr())) {
			continue
		}
		result = append(result, route.String())
	}
	return result
}

func addressStrings(addresses []netip.Addr) []string {
	result := make([]string, 0, len(addresses))
	for _, address := range addresses {
		result = append(result, address.String())
	}
	return result
}
