package config

import (
	"errors"
	"os"
	"testing"
	"time"
)

func clearAuthEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{
		"SERVICE_BUS_CONNECTION_STRING",
		"SERVICE_BUS_NAMESPACE",
		"STORAGE_CONNECTION_STRING",
		"STORAGE_ACCOUNT_URL",
		"STORAGE_CONTAINER_NAME",
		"PACKER_PATH",
		"PACKING_WORK_ROOT",
		"PACKING_RECEIVE_TIMEOUT",
		"PACKING_LOCK_RENEW_INTERVAL",
	} {
		t.Setenv(key, "")
	}
}

func setValidAuth(t *testing.T) {
	t.Helper()
	clearAuthEnv(t)
	t.Setenv("SERVICE_BUS_CONNECTION_STRING", "Endpoint=sb://example.servicebus.windows.net/;SharedAccessKeyName=x;SharedAccessKey=y")
	t.Setenv("STORAGE_CONNECTION_STRING", "DefaultEndpointsProtocol=https;AccountName=example;AccountKey=z")
}

func TestLoadDefaults(t *testing.T) {
	setValidAuth(t)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	if cfg.DispatchQueue != "packing-dispatch" {
		t.Errorf("DispatchQueue = %q, want packing-dispatch", cfg.DispatchQueue)
	}
	if cfg.ResultQueue != "packing-results" {
		t.Errorf("ResultQueue = %q, want packing-results", cfg.ResultQueue)
	}
	if cfg.StorageContainer != "models" {
		t.Errorf("StorageContainer = %q, want models", cfg.StorageContainer)
	}
	if cfg.PackerPath != "/usr/local/bin/packer" {
		t.Errorf("PackerPath = %q, want /usr/local/bin/packer", cfg.PackerPath)
	}
	if cfg.WorkRoot != os.TempDir() {
		t.Errorf("WorkRoot = %q, want %q", cfg.WorkRoot, os.TempDir())
	}
	if cfg.ReceiveTimeout != time.Minute {
		t.Errorf("ReceiveTimeout = %v, want 1m", cfg.ReceiveTimeout)
	}
	if cfg.LockRenewInterval != 20*time.Second {
		t.Errorf("LockRenewInterval = %v, want 20s", cfg.LockRenewInterval)
	}
}

func TestLoadAppliesOverrides(t *testing.T) {
	setValidAuth(t)
	t.Setenv("STORAGE_CONTAINER_NAME", "custom-models")
	t.Setenv("PACKER_PATH", "/opt/packer/bin/packer")
	t.Setenv("PACKING_WORK_ROOT", "/var/tmp/packing")
	t.Setenv("PACKING_RECEIVE_TIMEOUT", "90s")
	t.Setenv("PACKING_LOCK_RENEW_INTERVAL", "5s")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	if cfg.StorageContainer != "custom-models" {
		t.Errorf("StorageContainer = %q, want custom-models", cfg.StorageContainer)
	}
	if cfg.PackerPath != "/opt/packer/bin/packer" {
		t.Errorf("PackerPath = %q, want /opt/packer/bin/packer", cfg.PackerPath)
	}
	if cfg.WorkRoot != "/var/tmp/packing" {
		t.Errorf("WorkRoot = %q, want /var/tmp/packing", cfg.WorkRoot)
	}
	if cfg.ReceiveTimeout != 90*time.Second {
		t.Errorf("ReceiveTimeout = %v, want 90s", cfg.ReceiveTimeout)
	}
	if cfg.LockRenewInterval != 5*time.Second {
		t.Errorf("LockRenewInterval = %v, want 5s", cfg.LockRenewInterval)
	}
}

func TestLoadUsesServiceBusConnectionString(t *testing.T) {
	clearAuthEnv(t)
	t.Setenv("SERVICE_BUS_CONNECTION_STRING", "Endpoint=sb://example.servicebus.windows.net/;SharedAccessKeyName=x;SharedAccessKey=y")
	t.Setenv("STORAGE_ACCOUNT_URL", "https://example.blob.core.windows.net")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ServiceBusConnectionString == "" {
		t.Error("ServiceBusConnectionString should be populated")
	}
	if cfg.StorageAccountURL == "" {
		t.Error("StorageAccountURL should be populated")
	}
}

func TestLoadUsesServiceBusNamespace(t *testing.T) {
	clearAuthEnv(t)
	t.Setenv("SERVICE_BUS_NAMESPACE", "example.servicebus.windows.net")
	t.Setenv("STORAGE_ACCOUNT_URL", "https://example.blob.core.windows.net")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ServiceBusNamespace == "" {
		t.Error("ServiceBusNamespace should be populated")
	}
}

func TestLoadRejectsMissingServiceBusAuth(t *testing.T) {
	clearAuthEnv(t)
	t.Setenv("STORAGE_CONNECTION_STRING", "conn")

	_, err := Load()
	if !errors.Is(err, ErrServiceBusAuth) {
		t.Fatalf("expected ErrServiceBusAuth, got %v", err)
	}
}

func TestLoadRejectsBothServiceBusAuth(t *testing.T) {
	clearAuthEnv(t)
	t.Setenv("SERVICE_BUS_CONNECTION_STRING", "conn")
	t.Setenv("SERVICE_BUS_NAMESPACE", "ns.servicebus.windows.net")
	t.Setenv("STORAGE_CONNECTION_STRING", "conn")

	_, err := Load()
	if !errors.Is(err, ErrServiceBusAuth) {
		t.Fatalf("expected ErrServiceBusAuth, got %v", err)
	}
}

func TestLoadRejectsMissingStorageAuth(t *testing.T) {
	clearAuthEnv(t)
	t.Setenv("SERVICE_BUS_CONNECTION_STRING", "conn")

	_, err := Load()
	if !errors.Is(err, ErrStorageAuth) {
		t.Fatalf("expected ErrStorageAuth, got %v", err)
	}
}

func TestLoadRejectsBothStorageAuth(t *testing.T) {
	clearAuthEnv(t)
	t.Setenv("SERVICE_BUS_CONNECTION_STRING", "conn")
	t.Setenv("STORAGE_CONNECTION_STRING", "conn")
	t.Setenv("STORAGE_ACCOUNT_URL", "https://example.blob.core.windows.net")

	_, err := Load()
	if !errors.Is(err, ErrStorageAuth) {
		t.Fatalf("expected ErrStorageAuth, got %v", err)
	}
}

func TestLoadRejectsInvalidReceiveTimeout(t *testing.T) {
	setValidAuth(t)
	t.Setenv("PACKING_RECEIVE_TIMEOUT", "not-a-duration")

	_, err := Load()
	if err == nil {
		t.Fatal("expected an error for invalid PACKING_RECEIVE_TIMEOUT")
	}
}

func TestLoadRejectsInvalidLockRenewInterval(t *testing.T) {
	setValidAuth(t)
	t.Setenv("PACKING_LOCK_RENEW_INTERVAL", "not-a-duration")

	_, err := Load()
	if err == nil {
		t.Fatal("expected an error for invalid PACKING_LOCK_RENEW_INTERVAL")
	}
}
