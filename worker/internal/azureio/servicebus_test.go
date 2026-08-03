package azureio

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/messaging/azservicebus"

	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

const (
	testJobID          = "00000000-0000-0000-0000-000000000001"
	testEngineVersion  = "packer 0.3.0"
	testEngineChecksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	testResultChecksum = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
)

// fakeReceiver and fakeSender stand in for the SDK types so every test here
// runs with no broker. Each records what it was handed and answers with what
// the test scripted; the guarded fields are read by the test goroutine after
// the call under test returns, and by the renewal fake concurrently.
type fakeReceiver struct {
	mu sync.Mutex

	messages   []*azservicebus.ReceivedMessage
	receiveErr error
	// blockReceive holds ReceiveMessages until its context ends, which is how
	// a receive window that closes empty is simulated.
	blockReceive bool
	receiveCalls int
	maxMessages  int

	renewErr error
	// blockRenew holds RenewMessageLock until its context ends, so a test can
	// prove the context reaches the SDK call rather than being dropped.
	blockRenew   bool
	renewed      []*azservicebus.ReceivedMessage
	completed    []*azservicebus.ReceivedMessage
	abandoned    []*azservicebus.ReceivedMessage
	completeErr  error
	abandonErr   error
	closed       bool
	closeErr     error
	renewEntered chan struct{}
}

func (f *fakeReceiver) ReceiveMessages(ctx context.Context, maxMessages int, _ *azservicebus.ReceiveMessagesOptions) ([]*azservicebus.ReceivedMessage, error) {
	f.mu.Lock()
	f.receiveCalls++
	f.maxMessages = maxMessages
	block, messages, err := f.blockReceive, f.messages, f.receiveErr
	f.mu.Unlock()

	if block {
		<-ctx.Done()
		return nil, ctx.Err()
	}
	return messages, err
}

func (f *fakeReceiver) RenewMessageLock(ctx context.Context, msg *azservicebus.ReceivedMessage, _ *azservicebus.RenewMessageLockOptions) error {
	f.mu.Lock()
	f.renewed = append(f.renewed, msg)
	block, err, entered := f.blockRenew, f.renewErr, f.renewEntered
	f.mu.Unlock()

	if entered != nil {
		close(entered)
	}
	if block {
		<-ctx.Done()
		return ctx.Err()
	}
	return err
}

func (f *fakeReceiver) CompleteMessage(_ context.Context, msg *azservicebus.ReceivedMessage, _ *azservicebus.CompleteMessageOptions) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.completed = append(f.completed, msg)
	return f.completeErr
}

func (f *fakeReceiver) AbandonMessage(_ context.Context, msg *azservicebus.ReceivedMessage, _ *azservicebus.AbandonMessageOptions) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.abandoned = append(f.abandoned, msg)
	return f.abandonErr
}

func (f *fakeReceiver) Close(context.Context) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return f.closeErr
}

type fakeSender struct {
	mu       sync.Mutex
	sent     []*azservicebus.Message
	sendErr  error
	closed   bool
	closeErr error
}

func (f *fakeSender) SendMessage(_ context.Context, msg *azservicebus.Message, _ *azservicebus.SendMessageOptions) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, msg)
	return f.sendErr
}

func (f *fakeSender) Close(context.Context) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return f.closeErr
}

// foreignDelivery is a pipeline.Delivery this adapter did not issue.
type foreignDelivery struct{ body []byte }

func (d foreignDelivery) Body() []byte { return d.body }

func newTestQueue(receiveTimeout time.Duration) (*ServiceBusQueue, *fakeReceiver, *fakeSender) {
	receiver := &fakeReceiver{}
	sender := &fakeSender{}
	return newServiceBusQueue(receiver, sender, receiveTimeout), receiver, sender
}

func startedEvent() contracts.WorkerEvent {
	return contracts.WorkerEvent{
		MessageVersion: contracts.MessageVersion,
		EventType:      "started",
		JobID:          testJobID,
		EngineVersion:  testEngineVersion,
		EngineChecksum: testEngineChecksum,
	}
}

func succeededEvent() contracts.WorkerEvent {
	fileName := "output.bin"
	contentType := "application/octet-stream"
	size := int64(12)
	checksum := testResultChecksum
	return contracts.WorkerEvent{
		MessageVersion:    contracts.MessageVersion,
		EventType:         "succeeded",
		JobID:             testJobID,
		EngineVersion:     testEngineVersion,
		EngineChecksum:    testEngineChecksum,
		ResultFileName:    &fileName,
		ResultContentType: &contentType,
		ResultSizeBytes:   &size,
		ResultChecksum:    &checksum,
	}
}

func failedEvent() contracts.WorkerEvent {
	reason := "packer exited with code 2"
	return contracts.WorkerEvent{
		MessageVersion: contracts.MessageVersion,
		EventType:      "failed",
		JobID:          testJobID,
		EngineVersion:  testEngineVersion,
		EngineChecksum: testEngineChecksum,
		Reason:         &reason,
	}
}

func TestMessageBodyMatchesTheContractEncoders(t *testing.T) {
	started, err := contracts.EncodeStarted(testJobID, testEngineVersion, testEngineChecksum)
	if err != nil {
		t.Fatalf("EncodeStarted: %v", err)
	}
	succeeded, err := contracts.EncodeSucceeded(testJobID, testEngineVersion, testEngineChecksum, "output.bin", "application/octet-stream", 12, testResultChecksum)
	if err != nil {
		t.Fatalf("EncodeSucceeded: %v", err)
	}
	failed, err := contracts.EncodeFailed(testJobID, testEngineVersion, testEngineChecksum, "packer exited with code 2")
	if err != nil {
		t.Fatalf("EncodeFailed: %v", err)
	}

	cases := []struct {
		name  string
		event contracts.WorkerEvent
		want  []byte
	}{
		{"started", startedEvent(), started},
		{"succeeded", succeededEvent(), succeeded},
		{"failed", failedEvent(), failed},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			queue, _, sender := newTestQueue(time.Second)
			if err := queue.SendEvent(context.Background(), tc.event); err != nil {
				t.Fatalf("SendEvent: %v", err)
			}
			if len(sender.sent) != 1 {
				t.Fatalf("sent %d messages, want 1", len(sender.sent))
			}
			if got := string(sender.sent[0].Body); got != string(tc.want) {
				t.Fatalf("body mismatch:\n got:  %s\n want: %s", got, tc.want)
			}
		})
	}
}

func TestMessageCarriesJsonContentType(t *testing.T) {
	queue, _, sender := newTestQueue(time.Second)
	if err := queue.SendEvent(context.Background(), startedEvent()); err != nil {
		t.Fatalf("SendEvent: %v", err)
	}
	contentType := sender.sent[0].ContentType
	if contentType == nil {
		t.Fatal("ContentType is nil, want application/json")
	}
	if *contentType != "application/json" {
		t.Fatalf("ContentType = %q, want application/json", *contentType)
	}
}

// The message id is what makes a resent started/succeeded pair idempotent
// when the broker redelivers a dispatch the worker already handled.
func TestMessageIDIsJobIDAndEventType(t *testing.T) {
	cases := []struct {
		name  string
		event contracts.WorkerEvent
		want  string
	}{
		{"started", startedEvent(), testJobID + ":started"},
		{"succeeded", succeededEvent(), testJobID + ":succeeded"},
		{"failed", failedEvent(), testJobID + ":failed"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			queue, _, sender := newTestQueue(time.Second)
			if err := queue.SendEvent(context.Background(), tc.event); err != nil {
				t.Fatalf("SendEvent: %v", err)
			}
			id := sender.sent[0].MessageID
			if id == nil {
				t.Fatalf("MessageID is nil, want %s", tc.want)
			}
			if *id != tc.want {
				t.Fatalf("MessageID = %q, want %q", *id, tc.want)
			}
		})
	}
}

// packing-results is session enabled, and the Java side rejects a message
// whose session id is not the decoded event's job id: PackingResultProcessor
// abandons it and PackingDeadLetterReconciler refuses to replay it, so every
// event would be discarded after five deliveries with the job never terminal.
func TestMessageSessionIDIsTheJobID(t *testing.T) {
	for _, event := range []contracts.WorkerEvent{startedEvent(), succeededEvent(), failedEvent()} {
		t.Run(event.EventType, func(t *testing.T) {
			queue, _, sender := newTestQueue(time.Second)
			if err := queue.SendEvent(context.Background(), event); err != nil {
				t.Fatalf("SendEvent: %v", err)
			}
			session := sender.sent[0].SessionID
			if session == nil {
				t.Fatalf("SessionID is nil, want %s", testJobID)
			}
			if *session != testJobID {
				t.Fatalf("SessionID = %q, want %q", *session, testJobID)
			}
		})
	}
}

// Every field crosses into a Java domain model that rejects blanks, so the
// adapter transports what it was handed and normalises nothing.
func TestSendEventDoesNotNormaliseEventFields(t *testing.T) {
	reason := "  leading and trailing  "
	event := contracts.WorkerEvent{
		MessageVersion: contracts.MessageVersion,
		EventType:      "failed",
		JobID:          testJobID,
		EngineVersion:  "  packer 0.3.0  ",
		EngineChecksum: testEngineChecksum,
		Reason:         &reason,
	}
	want, err := contracts.EncodeFailed(event.JobID, event.EngineVersion, event.EngineChecksum, reason)
	if err != nil {
		t.Fatalf("EncodeFailed: %v", err)
	}

	queue, _, sender := newTestQueue(time.Second)
	if err := queue.SendEvent(context.Background(), event); err != nil {
		t.Fatalf("SendEvent: %v", err)
	}
	if got := string(sender.sent[0].Body); got != string(want) {
		t.Fatalf("body mismatch:\n got:  %s\n want: %s", got, want)
	}
}

func TestSendEventReportsSendFailureWithoutRetrying(t *testing.T) {
	queue, _, sender := newTestQueue(time.Second)
	sendErr := errors.New("broker unavailable")
	sender.sendErr = sendErr

	err := queue.SendEvent(context.Background(), startedEvent())
	if !errors.Is(err, sendErr) {
		t.Fatalf("SendEvent error = %v, want it to wrap %v", err, sendErr)
	}
	if len(sender.sent) != 1 {
		t.Fatalf("sent %d messages, want exactly 1 attempt", len(sender.sent))
	}
}

// The job id comes from the decoded body and nowhere else. Subject, session
// and application properties are metadata a bug or an attacker controls; the
// body is the contract, and Delivery exposes nothing else.
func TestDispatchJobIDComesFromTheBodyNotTheMetadata(t *testing.T) {
	const otherJobID = "00000000-0000-0000-0000-0000000000ff"
	subject := otherJobID
	session := otherJobID

	queue, receiver, _ := newTestQueue(time.Second)
	receiver.messages = []*azservicebus.ReceivedMessage{{
		Body:                  []byte(`{"messageVersion":1,"jobId":"` + testJobID + `"}`),
		Subject:               &subject,
		SessionID:             &session,
		MessageID:             otherJobID,
		ApplicationProperties: map[string]any{"jobId": otherJobID},
	}}

	delivery, err := queue.ReceiveOne(context.Background())
	if err != nil {
		t.Fatalf("ReceiveOne: %v", err)
	}
	dispatch, err := contracts.DecodeDispatch(delivery.Body())
	if err != nil {
		t.Fatalf("DecodeDispatch: %v", err)
	}
	if dispatch.JobID != testJobID {
		t.Fatalf("jobId = %s, want %s from the body", dispatch.JobID, testJobID)
	}
}

func TestReceiveOneRequestsExactlyOneMessage(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Second)
	receiver.messages = []*azservicebus.ReceivedMessage{{Body: []byte(`{}`)}}

	if _, err := queue.ReceiveOne(context.Background()); err != nil {
		t.Fatalf("ReceiveOne: %v", err)
	}
	if receiver.maxMessages != 1 {
		t.Fatalf("maxMessages = %d, want 1", receiver.maxMessages)
	}
	if receiver.receiveCalls != 1 {
		t.Fatalf("receiveCalls = %d, want exactly 1 attempt", receiver.receiveCalls)
	}
}

// An empty window is the normal outcome of a one-shot worker, not a failure.
func TestReceiveOneReportsNoMessageOnAnEmptySlice(t *testing.T) {
	queue, _, _ := newTestQueue(time.Second)

	delivery, err := queue.ReceiveOne(context.Background())
	if !errors.Is(err, pipeline.ErrNoMessage) {
		t.Fatalf("ReceiveOne error = %v, want ErrNoMessage", err)
	}
	if delivery != nil {
		t.Fatalf("ReceiveOne delivery = %#v, want an untyped nil interface", delivery)
	}
}

// The processor guards with `delivery == nil`. A (*serviceBusDelivery)(nil)
// inside a non-nil interface defeats that guard and panics on Body().
func TestReceiveOneReturnsAnUntypedNilDelivery(t *testing.T) {
	cases := []struct {
		name    string
		arrange func(*fakeReceiver)
	}{
		{"empty slice", func(r *fakeReceiver) {}},
		{"nil message in slice", func(r *fakeReceiver) {
			r.messages = []*azservicebus.ReceivedMessage{nil}
		}},
		{"receive deadline expired", func(r *fakeReceiver) { r.blockReceive = true }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			queue, receiver, _ := newTestQueue(20 * time.Millisecond)
			tc.arrange(receiver)

			delivery, err := queue.ReceiveOne(context.Background())
			if !errors.Is(err, pipeline.ErrNoMessage) {
				t.Fatalf("ReceiveOne error = %v, want ErrNoMessage", err)
			}
			if delivery != nil {
				t.Fatalf("delivery interface is non-nil (%#v); the processor's nil guard would be defeated", delivery)
			}
		})
	}
}

// "The receive window closed empty" must stay distinguishable from "the
// parent context was cancelled" and from a transport error: Task 6 maps the
// three to different exit codes.
func TestReceiveOneDistinguishesParentCancellationFromAnEmptyWindow(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Minute)
	receiver.blockReceive = true

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := queue.ReceiveOne(ctx)
	if errors.Is(err, pipeline.ErrNoMessage) {
		t.Fatal("a cancelled parent context was reported as an empty receive window")
	}
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("ReceiveOne error = %v, want it to wrap context.Canceled", err)
	}
}

func TestReceiveOneReportsATransportFailure(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Minute)
	transportErr := errors.New("amqp: link detached")
	receiver.receiveErr = transportErr

	_, err := queue.ReceiveOne(context.Background())
	if errors.Is(err, pipeline.ErrNoMessage) {
		t.Fatal("a transport failure was reported as an empty receive window")
	}
	if !errors.Is(err, transportErr) {
		t.Fatalf("ReceiveOne error = %v, want it to wrap %v", err, transportErr)
	}
	if receiver.receiveCalls != 1 {
		t.Fatalf("receiveCalls = %d, want exactly 1 attempt", receiver.receiveCalls)
	}
}

func TestRenewLockPassesTheReceivedMessageThrough(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Second)
	message := &azservicebus.ReceivedMessage{Body: []byte(`{}`)}
	receiver.messages = []*azservicebus.ReceivedMessage{message}

	delivery, err := queue.ReceiveOne(context.Background())
	if err != nil {
		t.Fatalf("ReceiveOne: %v", err)
	}
	if err := queue.RenewLock(context.Background(), delivery); err != nil {
		t.Fatalf("RenewLock: %v", err)
	}
	if len(receiver.renewed) != 1 || receiver.renewed[0] != message {
		t.Fatalf("renewed %#v, want the received message", receiver.renewed)
	}
}

// The processor's renewal goroutine cancels this context to stop renewing and
// joins the goroutine before it settles. A RenewLock that drops the context
// hangs a one-shot container holding an unsettled message.
func TestRenewLockHonoursContextCancellation(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Second)
	receiver.blockRenew = true
	receiver.renewEntered = make(chan struct{})
	message := &azservicebus.ReceivedMessage{Body: []byte(`{}`)}
	receiver.messages = []*azservicebus.ReceivedMessage{message}

	delivery, err := queue.ReceiveOne(context.Background())
	if err != nil {
		t.Fatalf("ReceiveOne: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	returned := make(chan error, 1)
	go func() { returned <- queue.RenewLock(ctx, delivery) }()

	select {
	case <-receiver.renewEntered:
	case <-time.After(5 * time.Second):
		t.Fatal("RenewMessageLock was never called")
	}
	cancel()

	select {
	case err := <-returned:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("RenewLock error = %v, want it to wrap context.Canceled", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("RenewLock ignored its cancelled context and did not return")
	}
}

func TestRenewLockReportsAFailedRenewal(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Second)
	renewErr := errors.New("lock lost")
	receiver.renewErr = renewErr
	receiver.messages = []*azservicebus.ReceivedMessage{{Body: []byte(`{}`)}}

	delivery, err := queue.ReceiveOne(context.Background())
	if err != nil {
		t.Fatalf("ReceiveOne: %v", err)
	}
	if err := queue.RenewLock(context.Background(), delivery); !errors.Is(err, renewErr) {
		t.Fatalf("RenewLock error = %v, want it to wrap %v", err, renewErr)
	}
}

func TestCompleteAndAbandonSettleTheReceivedMessage(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Second)
	message := &azservicebus.ReceivedMessage{Body: []byte(`{}`)}
	receiver.messages = []*azservicebus.ReceivedMessage{message}

	delivery, err := queue.ReceiveOne(context.Background())
	if err != nil {
		t.Fatalf("ReceiveOne: %v", err)
	}
	if err := queue.Complete(context.Background(), delivery); err != nil {
		t.Fatalf("Complete: %v", err)
	}
	if err := queue.Abandon(context.Background(), delivery); err != nil {
		t.Fatalf("Abandon: %v", err)
	}
	if len(receiver.completed) != 1 || receiver.completed[0] != message {
		t.Fatalf("completed %#v, want the received message", receiver.completed)
	}
	if len(receiver.abandoned) != 1 || receiver.abandoned[0] != message {
		t.Fatalf("abandoned %#v, want the received message", receiver.abandoned)
	}
}

func TestSettlementReportsBrokerFailures(t *testing.T) {
	queue, receiver, _ := newTestQueue(time.Second)
	completeErr := errors.New("complete rejected")
	abandonErr := errors.New("abandon rejected")
	receiver.completeErr = completeErr
	receiver.abandonErr = abandonErr
	receiver.messages = []*azservicebus.ReceivedMessage{{Body: []byte(`{}`)}}

	delivery, err := queue.ReceiveOne(context.Background())
	if err != nil {
		t.Fatalf("ReceiveOne: %v", err)
	}
	if err := queue.Complete(context.Background(), delivery); !errors.Is(err, completeErr) {
		t.Fatalf("Complete error = %v, want it to wrap %v", err, completeErr)
	}
	if err := queue.Abandon(context.Background(), delivery); !errors.Is(err, abandonErr) {
		t.Fatalf("Abandon error = %v, want it to wrap %v", err, abandonErr)
	}
}

// A Delivery from somewhere else names no message this receiver can settle.
// That must read as a clear error rather than a panic.
func TestSettlementRejectsAForeignDelivery(t *testing.T) {
	deliveries := map[string]pipeline.Delivery{
		"foreign implementation": foreignDelivery{body: []byte(`{}`)},
		"typed nil wrapper":      (*serviceBusDelivery)(nil),
		"nil interface":          nil,
	}
	for name, delivery := range deliveries {
		t.Run(name, func(t *testing.T) {
			queue, receiver, _ := newTestQueue(time.Second)

			for op, call := range map[string]func(context.Context, pipeline.Delivery) error{
				"RenewLock": queue.RenewLock,
				"Complete":  queue.Complete,
				"Abandon":   queue.Abandon,
			} {
				if err := call(context.Background(), delivery); !errors.Is(err, ErrForeignDelivery) {
					t.Fatalf("%s error = %v, want ErrForeignDelivery", op, err)
				}
			}
			if len(receiver.renewed)+len(receiver.completed)+len(receiver.abandoned) != 0 {
				t.Fatal("a foreign delivery reached the SDK receiver")
			}
		})
	}
}

func TestCloseClosesBothLinks(t *testing.T) {
	queue, receiver, sender := newTestQueue(time.Second)
	if err := queue.Close(context.Background()); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if !receiver.closed || !sender.closed {
		t.Fatalf("closed receiver = %t, sender = %t, want both", receiver.closed, sender.closed)
	}
}

func TestCloseReportsBothFailures(t *testing.T) {
	queue, receiver, sender := newTestQueue(time.Second)
	receiverErr := errors.New("receiver close failed")
	senderErr := errors.New("sender close failed")
	receiver.closeErr = receiverErr
	sender.closeErr = senderErr

	err := queue.Close(context.Background())
	if !errors.Is(err, receiverErr) || !errors.Is(err, senderErr) {
		t.Fatalf("Close error = %v, want it to carry both close failures", err)
	}
}

// SERVICE_BUS_NAMESPACE is the bare namespace, matching the Java side, which
// appends the same suffix itself. config.Load already rejects one that is
// already fully qualified, so the suffix is appended unconditionally.
func TestServiceBusHostAppendsTheDomainSuffix(t *testing.T) {
	if got := serviceBusHost("packing-production"); got != "packing-production.servicebus.windows.net" {
		t.Fatalf("serviceBusHost = %q", got)
	}
}

func TestNewServiceBusClientUsesTheConnectionString(t *testing.T) {
	client, err := NewServiceBusClient(config.Config{
		ServiceBusConnectionString: "Endpoint=sb://packing.servicebus.windows.net/;SharedAccessKeyName=worker;SharedAccessKey=" + testEngineChecksum,
	})
	if err != nil {
		t.Fatalf("NewServiceBusClient: %v", err)
	}
	if client == nil {
		t.Fatal("NewServiceBusClient returned a nil client")
	}
}

func TestNewServiceBusClientRejectsAMalformedConnectionString(t *testing.T) {
	_, err := NewServiceBusClient(config.Config{ServiceBusConnectionString: "not-a-connection-string"})
	if err == nil {
		t.Fatal("NewServiceBusClient accepted a malformed connection string")
	}
}

func TestNewServiceBusClientFallsBackToTheNamespaceCredential(t *testing.T) {
	client, err := NewServiceBusClient(config.Config{ServiceBusNamespace: "packing-production"})
	if err != nil {
		t.Fatalf("NewServiceBusClient: %v", err)
	}
	if client == nil {
		t.Fatal("NewServiceBusClient returned a nil client")
	}
}
