// Package config loads the packing worker's configuration from environment
// variables. Queue names are fixed constants shared with the Java side and
// are not environment-configurable.
package config

import (
	"errors"
	"fmt"
	"os"
	"time"
)

const (
	defaultDispatchQueue     = "packing-dispatch"
	defaultResultQueue       = "packing-results"
	defaultStorageContainer  = "models"
	defaultPackerPath        = "/usr/local/bin/packer"
	defaultReceiveTimeout    = time.Minute
	defaultLockRenewInterval = 20 * time.Second
)

var (
	ErrServiceBusAuth = errors.New("config: exactly one of SERVICE_BUS_CONNECTION_STRING or SERVICE_BUS_NAMESPACE must be set")
	ErrStorageAuth    = errors.New("config: exactly one of STORAGE_CONNECTION_STRING or STORAGE_ACCOUNT_URL must be set")
)

type Config struct {
	ServiceBusConnectionString string
	ServiceBusNamespace        string
	DispatchQueue              string
	ResultQueue                string
	StorageConnectionString    string
	StorageAccountURL          string
	StorageContainer           string
	PackerPath                 string
	WorkRoot                   string
	ReceiveTimeout             time.Duration
	LockRenewInterval          time.Duration
}

func Load() (Config, error) {
	cfg := Config{
		ServiceBusConnectionString: os.Getenv("SERVICE_BUS_CONNECTION_STRING"),
		ServiceBusNamespace:        os.Getenv("SERVICE_BUS_NAMESPACE"),
		DispatchQueue:              defaultDispatchQueue,
		ResultQueue:                defaultResultQueue,
		StorageConnectionString:    os.Getenv("STORAGE_CONNECTION_STRING"),
		StorageAccountURL:          os.Getenv("STORAGE_ACCOUNT_URL"),
		StorageContainer:           defaultStorageContainer,
		PackerPath:                 defaultPackerPath,
		WorkRoot:                   os.TempDir(),
		ReceiveTimeout:             defaultReceiveTimeout,
		LockRenewInterval:          defaultLockRenewInterval,
	}

	if v := os.Getenv("STORAGE_CONTAINER_NAME"); v != "" {
		cfg.StorageContainer = v
	}
	if v := os.Getenv("PACKER_PATH"); v != "" {
		cfg.PackerPath = v
	}
	if v := os.Getenv("PACKING_WORK_ROOT"); v != "" {
		cfg.WorkRoot = v
	}
	if v := os.Getenv("PACKING_RECEIVE_TIMEOUT"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return Config{}, fmt.Errorf("config: invalid PACKING_RECEIVE_TIMEOUT %q: %w", v, err)
		}
		cfg.ReceiveTimeout = d
	}
	if v := os.Getenv("PACKING_LOCK_RENEW_INTERVAL"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return Config{}, fmt.Errorf("config: invalid PACKING_LOCK_RENEW_INTERVAL %q: %w", v, err)
		}
		cfg.LockRenewInterval = d
	}

	if isInvalidAuthPair(cfg.ServiceBusConnectionString, cfg.ServiceBusNamespace) {
		return Config{}, ErrServiceBusAuth
	}
	if isInvalidAuthPair(cfg.StorageConnectionString, cfg.StorageAccountURL) {
		return Config{}, ErrStorageAuth
	}

	return cfg, nil
}

// isInvalidAuthPair reports whether neither or both of two mutually
// exclusive auth settings are present.
func isInvalidAuthPair(a, b string) bool {
	return (a != "") == (b != "")
}
