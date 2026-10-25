package appnet

import (
	"errors"
	"os"
	"sync"
)

type Client struct {
	store    StateStore
	stateDir string

	mu        sync.Mutex
	opMutex   sync.Mutex
	closed    bool
	active    *session
	sessionID int64
}

func NewClient(stateDir string, store StateStore) (*Client, error) {
	if stateDir == "" || store == nil {
		return nil, errors.New("state directory and store are required")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return nil, publicError(err)
	}
	return &Client{
		store:    store,
		stateDir: stateDir,
	}, nil
}

func (c *Client) switchSession(next *session) error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		if next != nil {
			next.close()
		}
		return errors.New("client is closed")
	}
	old := c.active
	c.active = next
	if next != nil {
		c.sessionID++
	}
	c.mu.Unlock()
	if old != nil {
		old.close()
		return old.closeError()
	}
	return nil
}

func (c *Client) currentSession(expectedID int64) (*session, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.active == nil || c.active.kind == "" || c.sessionID != expectedID {
		return nil, errors.New("network session changed")
	}
	return c.active, nil
}

func (c *Client) Close() error {
	c.opMutex.Lock()
	defer c.opMutex.Unlock()
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	active := c.active
	c.active = nil
	c.mu.Unlock()
	if active != nil {
		active.close()
		return active.closeError()
	}
	return nil
}

func (c *Client) SessionID() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.active == nil {
		return 0
	}
	return c.sessionID
}
