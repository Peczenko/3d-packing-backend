// Package azureio adapts the packing worker's ports to Azure. It is the only
// package that imports the Azure SDK: pipeline and its processor stay free of
// it, which is why their tests need no cloud services and no broker.
package azureio

import (
	"fmt"

	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/messaging/azservicebus"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
)

// serviceBusDomainSuffix turns the bare SERVICE_BUS_NAMESPACE into the fully
// qualified host the SDK wants. The Java side's messaging configuration
// appends the same suffix to the same bare value, so the two agree by
// construction.
const serviceBusDomainSuffix = ".servicebus.windows.net"

// NewServiceBusClient picks the one authentication mode config.Load left set.
// That function already guarantees exactly one of the two is present and
// already rejects a namespace that is itself fully qualified, so neither is
// re-checked here.
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
