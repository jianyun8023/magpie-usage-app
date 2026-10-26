package appnet

import (
	"context"
	"net"
	"net/http"
	"sync"
	"sync/atomic"

	"tailscale.com/tsnet"
)

type session struct {
	kind      string
	dial      func(context.Context, string, string) (net.Conn, error)
	cancel    context.CancelFunc
	closeFn   func() error
	ctx       context.Context
	closed    atomic.Bool
	once      sync.Once
	err       error
	transport *http.Transport
	server    *tsnet.Server
}

func newSession(kind string, dial func(context.Context, string, string) (net.Conn, error), closeFn func() error) *session {
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{kind: kind, dial: dial, cancel: cancel, closeFn: closeFn, ctx: ctx}
	s.transport = &http.Transport{
		Proxy:                 nil,
		DialContext:           s.dialContext,
		ResponseHeaderTimeout: responseHeaderTimeout,
		MaxIdleConns:          20,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       idleConnTimeout,
	}
	return s
}

func (s *session) close() {
	if s == nil {
		return
	}
	s.once.Do(func() {
		s.closed.Store(true)
		s.cancel()
		if s.transport != nil {
			s.transport.CloseIdleConnections()
		}
		if s.closeFn != nil {
			s.err = s.closeFn()
		}
	})
}

func (s *session) closeError() error {
	if s == nil {
		return nil
	}
	return s.err
}

func (s *session) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if s == nil || s.closed.Load() {
		return nil, net.ErrClosed
	}
	dialCtx, cancel := context.WithTimeout(ctx, dialTimeout)
	stop := context.AfterFunc(s.ctx, cancel)
	defer stop()
	defer cancel()
	conn, err := s.dial(dialCtx, network, address)
	if err != nil && s.closed.Load() {
		return nil, net.ErrClosed
	}
	return conn, err
}
