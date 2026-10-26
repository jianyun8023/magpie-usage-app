package appnet

import "testing"

func TestDecodeIPListRejectsNull(t *testing.T) {
	if got, err := decodeIPList(`null`); err == nil || got != nil {
		t.Fatalf("decodeIPList(null) = %v, %v; want nil, error", got, err)
	}
}
