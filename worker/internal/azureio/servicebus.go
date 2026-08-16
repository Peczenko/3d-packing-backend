package azureio

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/messaging/azservicebus"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const eventContentType = "application/json"

var ErrForeignDelivery = errors.New("azureio: delivery was not issued by this queue")

type messageReceiver interface {
	ReceiveMessages(ctx context.Context, maxMessages int, options *azservicebus.ReceiveMessagesOptions) ([]*azservicebus.ReceivedMessage, error)
	RenewMessageLock(ctx context.Context, msg *azservicebus.ReceivedMessage, options *azservicebus.RenewMessageLockOptions) error
	CompleteMessage(ctx context.Context, msg *azservicebus.ReceivedMessage, options *azservicebus.CompleteMessageOptions) error
	AbandonMessage(ctx context.Context, msg *azservicebus.ReceivedMessage, options *azservicebus.AbandonMessageOptions) error
	Close(ctx context.Context) error
}

type eventSender interface {
	SendMessage(ctx context.Context, msg *azservicebus.Message, options *azservicebus.SendMessageOptions) error
	Close(ctx context.Context) error
}

type ServiceBusQueue struct {
	receiver       messageReceiver
	sender         eventSender
	receiveTimeout time.Duration
}

var _ pipeline.Queue = (*ServiceBusQueue)(nil)

var dispatchReceiverOptions = azservicebus.ReceiverOptions{
	ReceiveMode: azservicebus.ReceiveModePeekLock,
}

const unwindCloseTimeout = 10 * time.Second

func NewServiceBusQueue(client *azservicebus.Client, cfg config.Config) (*ServiceBusQueue, error) {
	options := dispatchReceiverOptions
	receiver, err := client.NewReceiverForQueue(cfg.DispatchQueue, &options)
	if err != nil {
		return nil, fmt.Errorf("azureio: open receiver for queue %s: %w", cfg.DispatchQueue, err)
	}
	sender, err := client.NewSender(cfg.ResultQueue, nil)
	if err != nil {
		closeCtx, abandonClose := context.WithTimeout(context.Background(), unwindCloseTimeout)
		defer abandonClose()
		closeErr := receiver.Close(closeCtx)
		return nil, errors.Join(fmt.Errorf("azureio: open sender for queue %s: %w", cfg.ResultQueue, err), closeErr)
	}
	return newServiceBusQueue(receiver, sender, cfg.ReceiveTimeout), nil
}

func newServiceBusQueue(receiver messageReceiver, sender eventSender, receiveTimeout time.Duration) *ServiceBusQueue {
	return &ServiceBusQueue{
		receiver:       receiver,
		sender:         sender,
		receiveTimeout: receiveTimeout,
	}
}

func (q *ServiceBusQueue) Close(ctx context.Context) error {
	return errors.Join(q.sender.Close(ctx), q.receiver.Close(ctx))
}

func (q *ServiceBusQueue) ReceiveOne(ctx context.Context) (pipeline.Delivery, error) {
	receiveCtx, closeWindow := context.WithTimeout(ctx, q.receiveTimeout)
	defer closeWindow()

	messages, err := q.receiver.ReceiveMessages(receiveCtx, 1, nil)
	if err != nil {
		if ctx.Err() != nil {
			return nil, fmt.Errorf("azureio: receive dispatch: %w", ctx.Err())
		}
		if receiveCtx.Err() != nil {
			return nil, pipeline.ErrNoMessage
		}
		return nil, fmt.Errorf("azureio: receive dispatch: %w", err)
	}
	if len(messages) == 0 || messages[0] == nil {
		// A literal nil, never a typed nil pointer: the processor's guard is
		// `delivery == nil`, and a (*serviceBusDelivery)(nil) inside a
		// non-nil interface would slip past it and panic on Body.
		return nil, pipeline.ErrNoMessage
	}
	return &serviceBusDelivery{message: messages[0]}, nil
}

func (q *ServiceBusQueue) RenewLock(ctx context.Context, delivery pipeline.Delivery) error {
	message, err := receivedMessage(delivery)
	if err != nil {
		return err
	}
	if err := q.receiver.RenewMessageLock(ctx, message, nil); err != nil {
		return fmt.Errorf("azureio: renew dispatch lock: %w", err)
	}
	return nil
}

func (q *ServiceBusQueue) SendEvent(ctx context.Context, event contracts.WorkerEvent) error {
	message, err := newEventMessage(event)
	if err != nil {
		return err
	}
	if err := q.sender.SendMessage(ctx, message, nil); err != nil {
		return fmt.Errorf("azureio: send %s event for job %s: %w", event.EventType, event.JobID, err)
	}
	return nil
}

func (q *ServiceBusQueue) Complete(ctx context.Context, delivery pipeline.Delivery) error {
	message, err := receivedMessage(delivery)
	if err != nil {
		return err
	}
	if err := q.receiver.CompleteMessage(ctx, message, nil); err != nil {
		return fmt.Errorf("azureio: complete dispatch: %w", err)
	}
	return nil
}

func (q *ServiceBusQueue) Abandon(ctx context.Context, delivery pipeline.Delivery) error {
	message, err := receivedMessage(delivery)
	if err != nil {
		return err
	}
	if err := q.receiver.AbandonMessage(ctx, message, nil); err != nil {
		return fmt.Errorf("azureio: abandon dispatch: %w", err)
	}
	return nil
}

func newEventMessage(event contracts.WorkerEvent) (*azservicebus.Message, error) {
	body, err := json.Marshal(event)
	if err != nil {
		return nil, fmt.Errorf("azureio: encode %s event for job %s: %w", event.EventType, event.JobID, err)
	}
	contentType := eventContentType
	messageID := event.JobID + ":" + event.EventType
	sessionID := event.JobID
	return &azservicebus.Message{
		Body:        body,
		ContentType: &contentType,
		MessageID:   &messageID,
		SessionID:   &sessionID,
	}, nil
}

type serviceBusDelivery struct {
	message *azservicebus.ReceivedMessage
}

func (d *serviceBusDelivery) Body() []byte {
	return d.message.Body
}

func receivedMessage(delivery pipeline.Delivery) (*azservicebus.ReceivedMessage, error) {
	wrapped, ok := delivery.(*serviceBusDelivery)
	if !ok || wrapped == nil || wrapped.message == nil {
		return nil, fmt.Errorf("%w: %T", ErrForeignDelivery, delivery)
	}
	return wrapped.message, nil
}
