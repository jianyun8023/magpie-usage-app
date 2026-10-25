package appnet

import (
	"encoding/json"
	"errors"
	"net/netip"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
)

func (c *Client) StartWireGuard(uapi, localIPsJSON, dnsIPsJSON string, mtu int64) error {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	c.mu.Lock()
	closed := c.closed
	c.mu.Unlock()
	if closed {
		return errors.New("client is closed")
	}
	if err := c.switchSession(nil); err != nil {
		return publicError(err)
	}
	locals, err := decodeIPList(localIPsJSON)
	if err != nil {
		return err
	}
	dnsIPs, err := decodeIPList(dnsIPsJSON)
	if err != nil {
		return err
	}
	if len(locals) == 0 || strings.TrimSpace(uapi) == "" || mtu < 576 || mtu > 65535 {
		return errors.New("invalid WireGuard parameters")
	}

	tunDev, netStack, err := wgnetstack.CreateNetTUN(locals, dnsIPs, int(mtu))
	if err != nil {
		return publicError(err)
	}
	wgDev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	if err := wgDev.IpcSet(uapi); err != nil {
		wgDev.Close()
		return errors.New("invalid WireGuard configuration")
	}
	if err := wgDev.Up(); err != nil {
		wgDev.Close()
		return publicError(err)
	}
	s := newSession("wireguard", netStack.DialContext, func() error {
		wgDev.Close()
		return nil
	})
	return c.switchSession(s)
}

func (c *Client) StopWireGuard() error {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	return c.stopActive("wireguard")
}

func decodeIPList(encoded string) ([]netip.Addr, error) {
	var values []string
	if err := json.Unmarshal([]byte(encoded), &values); err != nil {
		return nil, errors.New("invalid IP list")
	}
	ips := make([]netip.Addr, 0, len(values))
	for _, value := range values {
		ip, err := netip.ParseAddr(value)
		if err != nil {
			return nil, errors.New("invalid IP list")
		}
		ips = append(ips, ip.Unmap())
	}
	return ips, nil
}
