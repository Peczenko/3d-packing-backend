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

// eventContentType matches what the Java dispatch sender stamps on its own
// messages, so both directions of the packing wire describe themselves the
// same way.
const eventContentType = "application/json"

// ErrForeignDelivery reports a pipeline.Delivery this queue did not issue.
// Settling one is impossible — there is no SDK message behind it — so it is
// an error the caller can match on rather than a failed type assertion.
var ErrForeignDelivery = errors.New("azureio: delivery was not issued by this queue")

// messageReceiver and eventSender are the slices of *azservicebus.Receiver and
// *azservicebus.Sender this adapter uses. They exist so the adapter's own
// behaviour — the empty-window classification, the untyped nil, the context
// passed to a renewal, the shape of an outgoing message — is testable with no
// broker and no credentials.
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

// ServiceBusQueue receives one dispatch under a peek lock and sends lifecycle
// events on the result queue. Nothing here retries: a redelivery is what
// Abandon asks the broker for, and the broker's delivery count and
// dead-letter policy are what bound it.
type ServiceBusQueue struct {
	receiver       messageReceiver
	sender         eventSender
	receiveTimeout time.Duration
}

var _ pipeline.Queue = (*ServiceBusQueue)(nil)

func NewServiceBusQueue(client *azservicebus.Client, cfg config.Config) (*ServiceBusQueue, error) {
	// Peek lock is the SDK default, and is stated because the whole processor
	// depends on it: receive-and-delete would drop a job the moment it was
	// read, leaving nothing to abandon when the work failed.
	receiver, err := client.NewReceiverForQueue(cfg.DispatchQueue, &azservicebus.ReceiverOptions{
		ReceiveMode: azservicebus.ReceiveModePeekLock,
	})
	if err != nil {
		return nil, fmt.Errorf("azureio: open receiver for queue %s: %w", cfg.DispatchQueue, err)
	}
	sender, err := client.NewSender(cfg.ResultQueue, nil)
	if err != nil {
		closeErr := receiver.Close(context.Background())
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

// ReceiveOne takes at most one message, within its own receive window.
//
// Three outcomes have to stay apart, because the composition root turns them
// into different exit codes: the window closed with nothing in it (normal for
// a one-shot worker), the parent context was cancelled (a shutdown), and the
// transport failed. The parent is checked first, since its cancellation also
// ends the derived context and would otherwise read as an empty window.
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

// RenewLock passes the caller's context straight through. The processor
// cancels it to stop renewing and waits for this call to return before it
// settles, so a renewal that outlived its context would hang the worker with
// the message still locked.
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

// SendEvent waits for the broker to acknowledge the message. That
// acknowledgement is what the processor relies on before it starts the engine
// and before it completes the dispatch, so the call is never fired and
// forgotten and never retried here.
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

// newEventMessage carries the event across verbatim. Every field has already
// been validated by the processor against what the Java domain model accepts,
// and defaulting, trimming or normalising anything here would send bytes no
// test on either side of the wire has seen.
func newEventMessage(event contracts.WorkerEvent) (*azservicebus.Message, error) {
	body, err := json.Marshal(event)
	if err != nil {
		return nil, fmt.Errorf("azureio: encode %s event for job %s: %w", event.EventType, event.JobID, err)
	}
	contentType := eventContentType
	// "{jobId}:{eventType}" is what makes a resent started/succeeded pair
	// idempotent when the broker redelivers a dispatch whose result is
	// already in storage: duplicate detection sees the same id twice.
	messageID := event.JobID + ":" + event.EventType
	// packing-results is session enabled, and the Java side rejects a message
	// whose session id is not the decoded event's job id — the result
	// processor abandons it and the dead-letter reconciler refuses to replay
	// it, so the job would never reach a terminal state.
	sessionID := event.JobID
	return &azservicebus.Message{
		Body:        body,
		ContentType: &contentType,
		MessageID:   &messageID,
		SessionID:   &sessionID,
	}, nil
}

// serviceBusDelivery is the only Delivery this queue issues. It exposes the
// body and nothing else, so no caller can read a job id out of a subject, a
// session or an application property — metadata a bug or an attacker
// controls, where the body is the contract.
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
