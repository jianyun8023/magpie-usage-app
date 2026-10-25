package appnet

// StateStore is implemented by Android encrypted storage.
type StateStore interface {
	Read(key string) (string, error)
	Write(key string, value string) error
}

// Response is kept small so Java never owns a native stream.
type Response struct {
	StatusCode int64
	Body       string
}
