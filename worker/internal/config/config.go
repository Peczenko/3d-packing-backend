package config

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"time"
)

const (
	defaultDispatchQueue     = "packing-dispatch"
	defaultResultQueue       = "packing-results"
	defaultStorageContainer  = "models"
	defaultPackerPath        = "/usr/local/bin/packer"
	defaultReceiveTimeout    = time.Minute
	defaultLockRenewInterval = 20 * time.Second
	serviceBusDomainSuffix   = ".servicebus.windows.net"
)

var (
	ErrServiceBusAuth            = errors.New("config: exactly one of SERVICE_BUS_CONNECTION_STRING or SERVICE_BUS_NAMESPACE must be set")
	ErrStorageAuth               = errors.New("config: exactly one of STORAGE_CONNECTION_STRING or STORAGE_ACCOUNT_URL must be set")
	ErrServiceBusNamespaceFormat = errors.New("config: SERVICE_BUS_NAMESPACE must be a bare namespace, not a fully qualified domain name")
	ErrInvalidReceiveTimeout     = errors.New("config: PACKING_RECEIVE_TIMEOUT must be a positive duration")
	ErrInvalidLockRenewInterval  = errors.New("config: PACKING_LOCK_RENEW_INTERVAL must be a positive duration")
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

	if v := os.Getenv("PACKING_DISPATCH_QUEUE"); v != "" {
		cfg.DispatchQueue = v
	}
	if v := os.Getenv("PACKING_RESULT_QUEUE"); v != "" {
		cfg.ResultQueue = v
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
		d, err := parsePositiveDuration(v)
		if err != nil {
			return Config{}, fmt.Errorf("config: invalid PACKING_RECEIVE_TIMEOUT %q: %w: %w", v, ErrInvalidReceiveTimeout, err)
		}
		cfg.ReceiveTimeout = d
	}
	if v := os.Getenv("PACKING_LOCK_RENEW_INTERVAL"); v != "" {
		d, err := parsePositiveDuration(v)
		if err != nil {
			return Config{}, fmt.Errorf("config: invalid PACKING_LOCK_RENEW_INTERVAL %q: %w: %w", v, ErrInvalidLockRenewInterval, err)
		}
		cfg.LockRenewInterval = d
	}

	if isInvalidAuthPair(cfg.ServiceBusConnectionString, cfg.ServiceBusNamespace) {
		return Config{}, ErrServiceBusAuth
	}
	if isInvalidAuthPair(cfg.StorageConnectionString, cfg.StorageAccountURL) {
		return Config{}, ErrStorageAuth
	}
	if cfg.ServiceBusNamespace != "" && strings.Contains(strings.ToLower(cfg.ServiceBusNamespace), serviceBusDomainSuffix) {
		return Config{}, fmt.Errorf("config: SERVICE_BUS_NAMESPACE %q looks like a fully qualified domain name: %w", cfg.ServiceBusNamespace, ErrServiceBusNamespaceFormat)
	}

	return cfg, nil
}

func isInvalidAuthPair(a, b string) bool {
	return (a != "") == (b != "")
}

func parsePositiveDuration(v string) (time.Duration, error) {
	d, err := time.ParseDuration(v)
	if err != nil {
		return 0, err
	}
	if d <= 0 {
		return 0, fmt.Errorf("duration must be positive, got %s", d)
	}
	return d, nil
}
