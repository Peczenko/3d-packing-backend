package azureio

import (
	"fmt"

	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/messaging/azservicebus"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
)

const serviceBusDomainSuffix = ".servicebus.windows.net"

func NewServiceBusClient(cfg config.Config) (*azservicebus.Client, error) {
	if cfg.ServiceBusConnectionString != "" {
		client, err := azservicebus.NewClientFromConnectionString(cfg.ServiceBusConnectionString, nil)
		if err != nil {
			// The connection string carries a shared access key and is never
			// interpolated into an error or a log line.
			return nil, fmt.Errorf("azureio: build service bus client from connection string: %w", err)
		}
		return client, nil
	}

	credential, err := azidentity.NewDefaultAzureCredential(nil)
	if err != nil {
		return nil, fmt.Errorf("azureio: build default azure credential: %w", err)
	}
	client, err := azservicebus.NewClient(serviceBusHost(cfg.ServiceBusNamespace), credential, nil)
	if err != nil {
		return nil, fmt.Errorf("azureio: build service bus client for namespace %s: %w", cfg.ServiceBusNamespace, err)
	}
	return client, nil
}

func serviceBusHost(namespace string) string {
	return namespace + serviceBusDomainSuffix
}
