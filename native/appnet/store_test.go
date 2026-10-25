package appnet

import (
	"encoding/base64"
	"errors"
	"slices"
	"strings"
	"testing"

	"tailscale.com/ipn"
)

type storeTestMapStore struct {
	values map[string]string
}

func (s *storeTestMapStore) Read(key string) (string, error) {
	return s.values[key], nil
}

func (s *storeTestMapStore) Write(key string, value string) error {
	s.values[key] = value
	return nil
}

func TestStateStoreAdapterReadsLegacyRawStdBase64State(t *testing.T) {
	key := ipn.StateKey("x2-test-state")
	state := []byte{0x00, 0x01, 0x02, 0xfe, 0xff}
	store := &storeTestMapStore{values: map[string]string{
		"statestore-x2-test-state": base64.RawStdEncoding.EncodeToString(state),
	}}

	got, err := (stateStoreAdapter{store: store}).ReadState(key)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(got, state) {
		t.Fatalf("unexpected state: got %v want %v", got, state)
	}
}

func TestStateStoreAdapterWritesCompatibleRawStdBase64State(t *testing.T) {
	key := ipn.StateKey("x2-test-written")
	state := []byte("synthetic-state-without-credentials")
	store := &storeTestMapStore{values: make(map[string]string)}
	adapter := stateStoreAdapter{store: store}

	if err := adapter.WriteState(key, state); err != nil {
		t.Fatal(err)
	}
	encoded := store.values["statestore-x2-test-written"]
	if want := base64.RawStdEncoding.EncodeToString(state); encoded != want {
		t.Fatalf("unexpected encoded state: got %q want %q", encoded, want)
	}
	if encoded == "" || strings.ContainsRune(encoded, '=') {
		t.Fatalf("expected unpadded RawStdBase64 value, got %q", encoded)
	}
	got, err := adapter.ReadState(key)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(got, state) {
		t.Fatalf("unexpected state: got %q want %q", got, state)
	}
}

func TestStateStoreAdapterMissingAndDeletedStateNotExist(t *testing.T) {
	key := ipn.StateKey("x2-test-delete")
	store := &storeTestMapStore{values: make(map[string]string)}
	adapter := stateStoreAdapter{store: store}

	if _, err := adapter.ReadState(key); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("missing state error = %v, want ipn.ErrStateNotExist", err)
	}
	if err := adapter.WriteState(key, []byte("temporary")); err != nil {
		t.Fatal(err)
	}
	if err := adapter.WriteState(key, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := adapter.ReadState(key); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("deleted state error = %v, want ipn.ErrStateNotExist", err)
	}
}

func TestStateStoreAdapterInvalidBase64FailsWithoutCiphertext(t *testing.T) {
	key := ipn.StateKey("x2-test-invalid")
	ciphertext := "!!!synthetic-private-marker-7b2c!!!"
	store := &storeTestMapStore{values: map[string]string{
		"statestore-x2-test-invalid": ciphertext,
	}}

	_, err := (stateStoreAdapter{store: store}).ReadState(key)
	if err == nil {
		t.Fatal("expected invalid stored state error")
	}
	if strings.Contains(err.Error(), ciphertext) {
		t.Fatalf("error leaks stored ciphertext: %v", err)
	}
	if strings.Contains(err.Error(), "synthetic-private-marker-7b2c") {
		t.Fatalf("error leaks stored ciphertext marker: %v", err)
	}
}
