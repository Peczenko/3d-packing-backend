package config

import (
	"errors"
	"fmt"
	"os"
	"time"
)

const (
	defaultTemporalAddress   = "localhost:7233"
	defaultTemporalNamespace = "packing"
	defaultTaskQueue         = "packing-engine"
	defaultStorageContainer  = "models"
	defaultPackerPath        = "/usr/local/bin/packer"
	defaultHeartbeatInterval = 10 * time.Second
)

var (
	ErrStorageAuth              = errors.New("config: exactly one of STORAGE_CONNECTION_STRING or STORAGE_ACCOUNT_URL must be set")
	ErrInvalidHeartbeatInterval = errors.New("config: PACKING_HEARTBEAT_INTERVAL must be a positive duration")
)

type Config struct {
	TemporalAddress         string
	TemporalNamespace       string
	TaskQueue               string
	HeartbeatInterval       time.Duration
	StorageConnectionString string
	StorageAccountURL       string
	StorageContainer        string
	PackerPath              string
	WorkRoot                string
}

func Load() (Config, error) {
	cfg := Config{
		TemporalAddress:         envOr("TEMPORAL_ADDRESS", defaultTemporalAddress),
		TemporalNamespace:       envOr("TEMPORAL_NAMESPACE", defaultTemporalNamespace),
		TaskQueue:               envOr("PACKING_TASK_QUEUE", defaultTaskQueue),
		HeartbeatInterval:       defaultHeartbeatInterval,
		StorageConnectionString: os.Getenv("STORAGE_CONNECTION_STRING"),
		StorageAccountURL:       os.Getenv("STORAGE_ACCOUNT_URL"),
		StorageContainer:        envOr("STORAGE_CONTAINER_NAME", defaultStorageContainer),
		PackerPath:              envOr("PACKER_PATH", defaultPackerPath),
		WorkRoot:                envOr("PACKING_WORK_ROOT", os.TempDir()),
	}

	if v := os.Getenv("PACKING_HEARTBEAT_INTERVAL"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil || d <= 0 {
			return Config{}, fmt.Errorf("config: invalid PACKING_HEARTBEAT_INTERVAL %q: %w", v, ErrInvalidHeartbeatInterval)
		}
		cfg.HeartbeatInterval = d
	}

	if (cfg.StorageConnectionString != "") == (cfg.StorageAccountURL != "") {
		return Config{}, ErrStorageAuth
	}

	return cfg, nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
