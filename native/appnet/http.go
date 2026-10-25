package appnet

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"time"
)

const (
	requestTimeout        = 45 * time.Second
	dialTimeout           = 20 * time.Second
	responseHeaderTimeout = 40 * time.Second // A quota refresh may read vendors for up to 30 seconds.
	maxResponseBody       = 4 << 20
	maxRedirects          = 4
)

func (c *Client) HTTPGet(rawURL, cookie string) (*Response, error) {
	return c.httpGet(rawURL, cookie, c.SessionID())
}

func (c *Client) HTTPGetForSession(rawURL, cookie string, expectedID int64) (*Response, error) {
	return c.httpGet(rawURL, cookie, expectedID)
}

func (c *Client) httpGet(rawURL, cookie string, expectedID int64) (*Response, error) {
	return c.httpRequest(rawURL, "Cookie", cookie, http.MethodGet, expectedID)
}

// HTTPRequestForSession carries gateway authentication through the selected tunnel.
func (c *Client) HTTPRequestForSession(rawURL, authorization, method string, expectedID int64) (*Response, error) {
	if method != http.MethodGet && method != http.MethodPost {
		return nil, errors.New("unsupported HTTP method")
	}
	return c.httpRequest(rawURL, "Authorization", authorization, method, expectedID)
}

func (c *Client) httpRequest(rawURL, header, credential, method string, expectedID int64) (*Response, error) {
	session, err := c.currentSession(expectedID)
	if err != nil {
		return nil, err
	}
	transport := session.transport

	ctx, cancel := context.WithTimeout(session.ctx, requestTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, rawURL, nil)
	if err != nil {
		return nil, publicError(err)
	}
	req.Header.Set("Accept", "application/json")
	if credential != "" {
		req.Header.Set(header, credential)
	}
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= maxRedirects {
				return errors.New("too many redirects")
			}
			if req.URL.Scheme != "http" && req.URL.Scheme != "https" {
				return errors.New("unsupported redirect scheme")
			}
			old := via[0].URL
			if old.Scheme != req.URL.Scheme || old.Hostname() != req.URL.Hostname() || effectivePort(old) != effectivePort(req.URL) {
				return errors.New("cross-origin redirect")
			}
			return nil
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, publicError(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBody+1))
	if err != nil {
		return nil, publicError(err)
	}
	if len(body) > maxResponseBody {
		return nil, errors.New("response body too large")
	}
	return &Response{StatusCode: int64(resp.StatusCode), Body: string(body)}, nil
}

func effectivePort(u *url.URL) string {
	if p := u.Port(); p != "" {
		return p
	}
	if u.Scheme == "https" {
		return "443"
	}
	return "80"
}
