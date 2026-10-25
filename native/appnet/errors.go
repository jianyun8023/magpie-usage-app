package appnet

import "errors"

func publicError(err error) error {
	if err == nil {
		return nil
	}
	return errors.New("network operation failed")
}
