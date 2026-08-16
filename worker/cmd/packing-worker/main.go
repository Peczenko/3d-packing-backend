// Command packing-worker handles exactly one dispatch message and exits.
//
// Concurrency comes from running more of these processes — the Container Apps
// Job scaler in Azure, --scale locally — never from this binary taking a
// second message, and every retry belongs to Service Bus.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/Peczenko/3d-packing-backend/worker/internal/azureio"
	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/engine"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
)

// The exit codes a Container Apps Job reads to decide whether an execution
// succeeded and whether to retry it.
//
// exitProcessed covers a packing job the packer rejected, and that is the
// consequential line in this file: the job ran, a `failed` event was sent and
// the dispatch was completed, so the execution succeeded — the job failed, the
// worker did not. Retrying it would rerun a job that legitimately failed.
//
// exitProcessed also covers an empty receive window, which a persistently
// unreachable broker is indistinguishable from: the SDK reports a backoff that
// outlives the window as a deadline, and the queue adapter cannot tell that
// apart from an idle queue. Nothing is lost — no message was received, the
// queue is untouched, the next execution retries — but this exit code is
// therefore never the broker-health signal. Queue depth and oldest-message-age
// are.
//
// exitConfiguration means the process could not be assembled from its
// environment. Everything it covers is a pure function of the configuration
// and performs no I/O, so the same environment fails the same way and a retry
// cannot help.
const (
	exitProcessed      = 0
	exitInfrastructure = 1
	exitConfiguration  = 2
)

// closeTimeout bounds hanging up the broker links. It runs after the delivery
// has been settled, so it can only delay the exit, never change it.
const closeTimeout = 15 * time.Second

// oneShot is the single call this command makes into the pipeline.
// *pipeline.Processor is the implementation; the interface exists so the exit
// mapping is testable against a fake with no broker.
type oneShot interface {
	RunOnce(context.Context) error
}

// connector builds the worker from a loaded configuration and returns the
// close that unwinds it. run injects the Azure one.
type connector func(config.Config, *logger) (oneShot, func(context.Context) error, error)

func main() {
	// stop is called rather than deferred, because os.Exit runs no deferred
	// functions. On Windows SIGTERM is accepted here but never delivered; the
	// production image is Linux, where it is how the platform asks for a
	// shutdown.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	code := run(ctx, os.Args[1:], os.Stdout)
	stop()
	os.Exit(code)
}

func run(ctx context.Context, args []string, out io.Writer) int {
	return runWith(ctx, args, out, connectAzure)
}

func runWith(ctx context.Context, args []string, out io.Writer, connect connector) int {
	log := newLogger(out)

	if len(args) > 0 {
		// The count, never the arguments: this process is configured entirely
		// through the environment, and echoing an argument would put whatever
		// was typed on the command line into the log.
		log.error(fmt.Sprintf("packing-worker takes no arguments, got %d", len(args)))
		return exitConfiguration
	}

	cfg, err := config.Load()
	if err != nil {
		log.error("configuration is unusable: " + err.Error())
		return exitConfiguration
	}

	worker, closeWorker, err := connect(cfg, log)
	if err != nil {
		log.error("worker could not be built: " + err.Error())
		return exitConfiguration
	}
	defer func() {
		// Detached from ctx: after a shutdown the caller's context is already
		// canceled and the links still have to be closed.
		closeCtx, giveUp := context.WithTimeout(context.WithoutCancel(ctx), closeTimeout)
		defer giveUp()
		if err := closeWorker(closeCtx); err != nil {
			// Logged, never returned. By here the delivery has been settled —
			// or deliberately not — and failing to hang up cannot change what
			// happened to the job.
			log.error("closing the worker failed: " + err.Error())
		}
	}()

	// RunOnce, once. A second delivery belongs to a second process.
	switch err := worker.RunOnce(ctx); {
	case err == nil:
		log.info("dispatch handled")
		return exitProcessed
	case errors.Is(err, pipeline.ErrNoMessage):
		log.info("no dispatch message within the receive window")
		return exitProcessed
	case errors.Is(err, pipeline.ErrLockLost):
		// Nothing is settled on this path, by RunOnce or by anything here:
		// another delivery owns this job now, and both Complete and Abandon
		// would be a claim on a lock this process no longer holds.
		log.error("dispatch lock lost, delivery left unsettled: " + err.Error())
		return exitInfrastructure
	default:
		log.error("dispatch failed: " + err.Error())
		return exitInfrastructure
	}
}

func connectAzure(cfg config.Config, log *logger) (oneShot, func(context.Context) error, error) {
	client, err := azureio.NewServiceBusClient(cfg)
	if err != nil {
		return nil, nil, err
	}
	queue, err := azureio.NewServiceBusQueue(client, cfg)
	if err != nil {
		return nil, nil, errors.Join(err, closeNow(client.Close))
	}
	blobClient, err := azureio.NewBlobClient(cfg)
	if err != nil {
		return nil, nil, errors.Join(err, closeNow(queue.Close), closeNow(client.Close))
	}

	worker := pipeline.NewProcessor(
		&observedQueue{inner: queue, log: log},
		azureio.NewBlobArtifacts(blobClient, cfg),
		engine.NewRunner(cfg.PackerPath),
		// NewProcessor takes these explicitly rather than a config.Config, so
		// pipeline stays free of a dependency on this module's configuration.
		cfg.WorkRoot,
		cfg.LockRenewInterval,
	)
	// Reverse construction order: the sender and receiver hang off the client,
	// so the client is closed last. The blob client has nothing to close.
	closeWorker := func(ctx context.Context) error {
		return errors.Join(queue.Close(ctx), client.Close(ctx))
	}
	return worker, closeWorker, nil
}

// closeNow unwinds what a failed construction already built. It uses a context
// of its own because there is no worker context yet, and because a broken
// connection must not turn reporting the failure into a hang.
func closeNow(close func(context.Context) error) error {
	ctx, giveUp := context.WithTimeout(context.Background(), closeTimeout)
	defer giveUp()
	return close(ctx)
}

// observedQueue reports what became of the one delivery this process handles.
// The logging lives here rather than in the adapter — a port implementation
// has no opinion about a log — and the job id it records comes from decoding
// the message body, which is the only thing that names the job: RunOnce
// returns an error, not an id.
type observedQueue struct {
	inner pipeline.Queue
	log   *logger
}

var _ pipeline.Queue = (*observedQueue)(nil)

func (q *observedQueue) ReceiveOne(ctx context.Context) (pipeline.Delivery, error) {
	delivery, err := q.inner.ReceiveOne(ctx)
	// Returned exactly as it came, never rewrapped: the real queue settles a
	// delivery by asserting its own type, so a wrapper would make Complete and
	// Abandon fail with ErrForeignDelivery. Passing the interface value
	// straight through also preserves the untyped nil ReceiveOne promises.
	if err != nil || delivery == nil {
		return delivery, err
	}
	// A body this worker cannot decode still gets a line; it just has no job
	// to name. The processor reports the decode failure itself.
	if dispatch, decodeErr := contracts.DecodeDispatch(delivery.Body()); decodeErr == nil {
		q.log.job(dispatch.JobID)
	}
	q.log.info("dispatch received")
	return delivery, nil
}

// RenewLock is deliberately silent. It is the one call the processor makes
// from a goroutine of its own, on a timer, for as long as the packer runs.
func (q *observedQueue) RenewLock(ctx context.Context, delivery pipeline.Delivery) error {
	return q.inner.RenewLock(ctx, delivery)
}

func (q *observedQueue) SendEvent(ctx context.Context, event contracts.WorkerEvent) error {
	if err := q.inner.SendEvent(ctx, event); err != nil {
		return err
	}
	// The event type alone — never the reason, the file name or a checksum. A
	// `failed` line here is what distinguishes a rejected packing job from a
	// packed one in the log, since both exit 0.
	q.log.info(event.EventType + " event sent")
	return nil
}

func (q *observedQueue) Complete(ctx context.Context, delivery pipeline.Delivery) error {
	if err := q.inner.Complete(ctx, delivery); err != nil {
		return err
	}
	q.log.info("dispatch completed")
	return nil
}

func (q *observedQueue) Abandon(ctx context.Context, delivery pipeline.Delivery) error {
	if err := q.inner.Abandon(ctx, delivery); err != nil {
		return err
	}
	q.log.info("dispatch abandoned for redelivery")
	return nil
}

// logger writes newline-delimited JSON. Its fields are a level, a message and
// — once a dispatch has been received and decoded — the job id. Nothing else
// may reach it: not the request body, not the spec, not a connection string.
type logger struct {
	mu      sync.Mutex
	encoder *json.Encoder
	jobID   string
}

type logLine struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	JobID   string `json:"jobId,omitempty"`
}

func newLogger(out io.Writer) *logger {
	encoder := json.NewEncoder(out)
	// These messages carry error text and file paths, not HTML; escaping < and
	// & would only make them harder to read.
	encoder.SetEscapeHTML(false)
	return &logger{encoder: encoder}
}

// job names the dispatch that every later line belongs to.
func (l *logger) job(jobID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.jobID = jobID
}

func (l *logger) info(message string) { l.write("info", message) }

func (l *logger) error(message string) { l.write("error", message) }

// write is mutex-guarded because the processor renews the delivery's lock from
// a goroutine of its own: nothing may interleave half a line or race the job
// id.
func (l *logger) write(level, message string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	// Best effort: a process whose log sink is broken has nowhere to report
	// that it could not report.
	_ = l.encoder.Encode(logLine{Level: level, Message: message, JobID: l.jobID})
}
