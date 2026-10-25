package appnet

import (
	"net"
	"testing"
)

type testInterfaceProvider struct{}

func (testInterfaceProvider) InterfacesJSON() (string, error) {
	return `[{"name":"wlan0","index":3,"mtu":1500,"up":true,"addrs":[{"ip":"192.0.2.10","prefixLen":24}]}]`, nil
}

func TestSetInterfaceProviderRejectsNilAndDuplicates(t *testing.T) {
	if err := SetInterfaceProvider(nil); err == nil {
		t.Fatal("expected nil provider rejection")
	}
	if err := SetInterfaceProvider(testInterfaceProvider{}); err != nil {
		t.Fatal(err)
	}
	if err := SetInterfaceProvider(testInterfaceProvider{}); err == nil {
		t.Fatal("expected duplicate provider rejection")
	}
}

func TestParseInterfacesJSONBuildsNetmonInterfaces(t *testing.T) {
	raw := `[{"name":"wlan0","index":3,"mtu":1500,"up":true,"broadcast":true,"loopback":false,"pointToPoint":false,"multicast":true,"addrs":[{"ip":"192.0.2.10","prefixLen":24},{"ip":"2001:db8::1","prefixLen":64},{"ip":"fe80::1%wlan0","prefixLen":64}]}]`
	ifaces, err := parseInterfacesJSON([]byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	if len(ifaces) != 1 {
		t.Fatalf("interface count = %d", len(ifaces))
	}
	iface := ifaces[0]
	if iface.Interface.Name != "wlan0" || iface.Interface.Index != 3 || iface.Interface.MTU != 1500 {
		t.Fatalf("unexpected interface: %+v", iface.Interface)
	}
	flags := iface.Interface.Flags
	if flags&net.FlagUp == 0 || flags&net.FlagBroadcast == 0 || flags&net.FlagMulticast == 0 || flags&net.FlagLoopback != 0 || flags&net.FlagPointToPoint != 0 {
		t.Fatalf("unexpected flags: %v", iface.Interface.Flags)
	}
	addrs, err := iface.Addrs()
	if err != nil {
		t.Fatal(err)
	}
	if len(addrs) != 3 {
		t.Fatalf("address count = %d", len(addrs))
	}
	ipNet, ok := addrs[0].(*net.IPNet)
	if !ok || ipNet.String() != "192.0.2.10/24" {
		t.Fatalf("unexpected IPv4 address: %#v", addrs[0])
	}
	ipNet, ok = addrs[1].(*net.IPNet)
	if !ok || ipNet.String() != "2001:db8::1/64" {
		t.Fatalf("unexpected IPv6 address: %#v", addrs[1])
	}
	ipNet, ok = addrs[2].(*net.IPNet)
	if !ok || ipNet.String() != "fe80::1/64" {
		t.Fatalf("unexpected zoned IPv6 address: %#v", addrs[2])
	}
}

func TestParseInterfacesJSONRejectsInvalidData(t *testing.T) {
	for name, raw := range map[string]string{
		"json":      `not-json`,
		"name":      `[{"name":"","index":1,"mtu":1500,"addrs":[]}]`,
		"index":     `[{"name":"wlan0","index":0,"mtu":1500,"addrs":[]}]`,
		"ip":        `[{"name":"wlan0","index":1,"mtu":1500,"addrs":[{"ip":"not-an-ip","prefixLen":24}]}]`,
		"ipv4-bits": `[{"name":"wlan0","index":1,"mtu":1500,"addrs":[{"ip":"192.0.2.1","prefixLen":33}]}]`,
		"ipv6-bits": `[{"name":"wlan0","index":1,"mtu":1500,"addrs":[{"ip":"2001:db8::1","prefixLen":129}]}]`,
		"neg-bits":  `[{"name":"wlan0","index":1,"mtu":1500,"addrs":[{"ip":"192.0.2.1","prefixLen":-1}]}]`,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseInterfacesJSON([]byte(raw)); err == nil {
				t.Fatal("expected invalid interface data error")
			}
		})
	}
}
