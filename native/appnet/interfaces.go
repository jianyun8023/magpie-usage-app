package appnet

import (
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"

	"tailscale.com/net/netmon"
)

// InterfaceProvider supplies live Android interface state. The getter must
// query Java on every call; netmon does not accept a cached snapshot.
type InterfaceProvider interface {
	InterfacesJSON() (string, error)
}

var interfaceProvider atomic.Pointer[InterfaceProvider]

// SetInterfaceProvider installs the Android interface bridge. It must be
// called before the first Client is created and can only be set once.
func SetInterfaceProvider(provider InterfaceProvider) error {
	if provider == nil {
		return errors.New("interface provider is required")
	}
	providerValue := provider
	if !interfaceProvider.CompareAndSwap(nil, &providerValue) {
		return errors.New("interface provider already installed")
	}
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		current := *interfaceProvider.Load()
		raw, err := current.InterfacesJSON()
		if err != nil {
			return nil, publicError(err)
		}
		return parseInterfacesJSON([]byte(raw))
	})
	return nil
}

type interfaceJSON struct {
	Name         string        `json:"name"`
	Index        int           `json:"index"`
	MTU          int           `json:"mtu"`
	Up           bool          `json:"up"`
	Broadcast    bool          `json:"broadcast"`
	Loopback     bool          `json:"loopback"`
	PointToPoint bool          `json:"pointToPoint"`
	Multicast    bool          `json:"multicast"`
	Addresses    []addressJSON `json:"addrs"`
}

type addressJSON struct {
	IP        string `json:"ip"`
	PrefixLen int    `json:"prefixLen"`
}

func parseInterfacesJSON(raw []byte) ([]netmon.Interface, error) {
	var input []interfaceJSON
	if err := json.Unmarshal(raw, &input); err != nil {
		return nil, errors.New("invalid interface data")
	}
	result := make([]netmon.Interface, 0, len(input))
	for _, item := range input {
		if item.Name == "" || item.Index <= 0 || item.MTU < 0 {
			return nil, errors.New("invalid interface data")
		}
		flags := net.Flags(0)
		if item.Up {
			flags |= net.FlagUp
		}
		if item.Broadcast {
			flags |= net.FlagBroadcast
		}
		if item.Loopback {
			flags |= net.FlagLoopback
		}
		if item.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if item.Multicast {
			flags |= net.FlagMulticast
		}
		addresses := make([]net.Addr, 0, len(item.Addresses))
		for _, address := range item.Addresses {
			ip, err := netip.ParseAddr(strings.TrimSpace(address.IP))
			if err != nil {
				return nil, errors.New("invalid interface address")
			}
			bitLen := ip.BitLen()
			if address.PrefixLen < 0 || address.PrefixLen > int(bitLen) {
				return nil, errors.New("invalid interface address")
			}
			ip = ip.WithZone("")
			network := &net.IPNet{
				IP:   ip.AsSlice(),
				Mask: net.CIDRMask(address.PrefixLen, int(bitLen)),
			}
			addresses = append(addresses, network)
		}
		result = append(result, netmon.Interface{
			Interface: &net.Interface{
				Index: item.Index,
				MTU:   item.MTU,
				Name:  item.Name,
				Flags: flags,
			},
			AltAddrs: addresses,
		})
	}
	return result, nil
}
