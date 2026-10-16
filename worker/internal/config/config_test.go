package config

import (
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

func clearEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{
		"TEMPORAL_ADDRESS",
		"TEMPORAL_NAMESPACE",
		"PACKING_TASK_QUEUE",
		"PACKING_HEARTBEAT_INTERVAL",
		"STORAGE_CONNECTION_STRING",
		"STORAGE_ACCOUNT_URL",
		"STORAGE_CONTAINER_NAME",
		"PACKER_PATH",
		"PACKING_WORK_ROOT",
		"PACKER_VERSION",
		"PACKING_ENGINE_OVERRUN_GRACE",
	} {
		t.Setenv(key, "")
	}
}

func TestLoadDefaults(t *testing.T) {
	clearEnv(t)
	t.Setenv("STORAGE_CONNECTION_STRING", "DefaultEndpointsProtocol=https;AccountName=example;AccountKey=z")
	t.Setenv("PACKER_VERSION", "v0.0.2")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	want := Config{
		TemporalAddress:         "localhost:7233",
		TemporalNamespace:       "packing",
		TaskQueue:               "packing-engine",
		HeartbeatInterval:       10 * time.Second,
		OverrunGrace:            60 * time.Second,
		StorageConnectionString: "DefaultEndpointsProtocol=https;AccountName=example;AccountKey=z",
		StorageContainer:        "models",
		PackerPath:              "/usr/local/bin/packer",
		PackerVersion:           "v0.0.2",
		WorkRoot:                os.TempDir(),
	}
	if cfg != want {
		t.Fatalf("Load() = %+v, want %+v", cfg, want)
	}
}

func TestLoadAppliesOverrides(t *testing.T) {
	clearEnv(t)
	t.Setenv("TEMPORAL_ADDRESS", "temporal:7233")
	t.Setenv("TEMPORAL_NAMESPACE", "other")
	t.Setenv("PACKING_TASK_QUEUE", "custom-engine")
	t.Setenv("PACKING_HEARTBEAT_INTERVAL", "3s")
	t.Setenv("STORAGE_ACCOUNT_URL", "https://example.blob.core.windows.net")
	t.Setenv("STORAGE_CONTAINER_NAME", "custom-models")
	t.Setenv("PACKER_PATH", "/opt/packer/bin/packer")
	t.Setenv("PACKING_WORK_ROOT", "/var/tmp/packing")
	t.Setenv("PACKER_VERSION", " v9.9.9 ")
	t.Setenv("PACKING_ENGINE_OVERRUN_GRACE", "90s")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	want := Config{
		TemporalAddress:   "temporal:7233",
		TemporalNamespace: "other",
		TaskQueue:         "custom-engine",
		HeartbeatInterval: 3 * time.Second,
		OverrunGrace:      90 * time.Second,
		StorageAccountURL: "https://example.blob.core.windows.net",
		StorageContainer:  "custom-models",
		PackerPath:        "/opt/packer/bin/packer",
		PackerVersion:     "v9.9.9",
		WorkRoot:          "/var/tmp/packing",
	}
	if cfg != want {
		t.Fatalf("Load() = %+v, want %+v", cfg, want)
	}
}

func TestLoadRejectsMissingStorageAuth(t *testing.T) {
	clearEnv(t)

	if _, err := Load(); !errors.Is(err, ErrStorageAuth) {
		t.Fatalf("expected ErrStorageAuth, got %v", err)
	}
}

func TestLoadRejectsBothStorageAuth(t *testing.T) {
	clearEnv(t)
	t.Setenv("STORAGE_CONNECTION_STRING", "conn")
	t.Setenv("STORAGE_ACCOUNT_URL", "https://example.blob.core.windows.net")

	if _, err := Load(); !errors.Is(err, ErrStorageAuth) {
		t.Fatalf("expected ErrStorageAuth, got %v", err)
	}
}

func TestLoadRejectsAnUnusableHeartbeatInterval(t *testing.T) {
	for _, value := range []string{"not-a-duration", "0s", "-1s"} {
		t.Run(value, func(t *testing.T) {
			clearEnv(t)
			t.Setenv("STORAGE_CONNECTION_STRING", "conn")
			t.Setenv("PACKING_HEARTBEAT_INTERVAL", value)

			if _, err := Load(); !errors.Is(err, ErrInvalidHeartbeatInterval) {
				t.Fatalf("expected ErrInvalidHeartbeatInterval, got %v", err)
			}
		})
	}
}

func TestLoadNeverEchoesTheStorageConnectionString(t *testing.T) {
	clearEnv(t)
	t.Setenv("STORAGE_CONNECTION_STRING", "AccountKey=SECRET-MARKER")
	t.Setenv("STORAGE_ACCOUNT_URL", "https://example.blob.core.windows.net")

	_, err := Load()
	if err == nil {
		t.Fatal("expected an error")
	}
	if got := err.Error(); strings.Contains(got, "SECRET-MARKER") {
		t.Fatalf("error leaks the connection string: %s", got)
	}
}

func TestLoadRejectsAMissingPackerVersion(t *testing.T) {
	clearEnv(t)
	t.Setenv("STORAGE_CONNECTION_STRING", "conn")

	if _, err := Load(); !errors.Is(err, ErrMissingPackerVersion) {
		t.Fatalf("expected ErrMissingPackerVersion, got %v", err)
	}
}

func TestLoadRejectsAnUnusableOverrunGrace(t *testing.T) {
	for _, value := range []string{"soon", "-1s"} {
		t.Run(value, func(t *testing.T) {
			clearEnv(t)
			t.Setenv("STORAGE_CONNECTION_STRING", "conn")
			t.Setenv("PACKER_VERSION", "v0.0.2")
			t.Setenv("PACKING_ENGINE_OVERRUN_GRACE", value)

			if _, err := Load(); !errors.Is(err, ErrInvalidOverrunGrace) {
				t.Fatalf("expected ErrInvalidOverrunGrace, got %v", err)
			}
		})
	}
}
