package appnet

import (
	"encoding/base64"
	"errors"
	"strings"

	"tailscale.com/ipn"
)

const legacyPrefix = "statestore-"

type stateStoreAdapter struct {
	store StateStore
}

func (s stateStoreAdapter) ReadState(key ipn.StateKey) ([]byte, error) {
	value, err := s.store.Read(legacyKey(key))
	if err != nil {
		return nil, err
	}
	if value == "" {
		return nil, ipn.ErrStateNotExist
	}
	data, err := base64.RawStdEncoding.DecodeString(value)
	if err != nil {
		return nil, errors.New("invalid stored state")
	}
	return data, nil
}

func (s stateStoreAdapter) WriteState(key ipn.StateKey, value []byte) error {
	if len(value) == 0 {
		return s.store.Write(legacyKey(key), "")
	}
	return s.store.Write(legacyKey(key), base64.RawStdEncoding.EncodeToString(value))
}

func legacyKey(key ipn.StateKey) string {
	return legacyPrefix + string(key)
}

func legacyKeyName(key string) string {
	return strings.TrimPrefix(key, legacyPrefix)
}
