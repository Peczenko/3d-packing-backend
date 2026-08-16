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

const (
	exitProcessed      = 0
	exitInfrastructure = 1
	exitConfiguration  = 2
)

const closeTimeout = 15 * time.Second

type oneShot interface {
	RunOnce(context.Context) error
}

type connector func(config.Config, *logger) (oneShot, func(context.Context) error, error)

func main() {
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
		closeCtx, giveUp := context.WithTimeout(context.WithoutCancel(ctx), closeTimeout)
		defer giveUp()
		if err := closeWorker(closeCtx); err != nil {
			// Logged, never returned. By here the delivery has been settled —
			// or deliberately not — and failing to hang up cannot change what
			// happened to the job.
			log.error("closing the worker failed: " + err.Error())
		}
	}()

	switch err := worker.RunOnce(ctx); {
	case err == nil:
		log.info("dispatch handled")
		return exitProcessed
	case errors.Is(err, pipeline.ErrNoMessage):
		log.info("no dispatch message within the receive window")
		return exitProcessed
	case errors.Is(err, pipeline.ErrLockLost):
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
		cfg.WorkRoot,
		cfg.LockRenewInterval,
	)
	closeWorker := func(ctx context.Context) error {
		return errors.Join(queue.Close(ctx), client.Close(ctx))
	}
	return worker, closeWorker, nil
}

func closeNow(close func(context.Context) error) error {
	ctx, giveUp := context.WithTimeout(context.Background(), closeTimeout)
	defer giveUp()
	return close(ctx)
}

type observedQueue struct {
	inner pipeline.Queue
	log   *logger
}

var _ pipeline.Queue = (*observedQueue)(nil)

func (q *observedQueue) ReceiveOne(ctx context.Context) (pipeline.Delivery, error) {
	delivery, err := q.inner.ReceiveOne(ctx)
	if err != nil || delivery == nil {
		return delivery, err
	}

	if dispatch, decodeErr := contracts.DecodeDispatch(delivery.Body()); decodeErr == nil {
		q.log.job(dispatch.JobID)
	}
	q.log.info("dispatch received")
	return delivery, nil
}

func (q *observedQueue) RenewLock(ctx context.Context, delivery pipeline.Delivery) error {
	return q.inner.RenewLock(ctx, delivery)
}

func (q *observedQueue) SendEvent(ctx context.Context, event contracts.WorkerEvent) error {
	if err := q.inner.SendEvent(ctx, event); err != nil {
		return err
	}
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
	encoder.SetEscapeHTML(false)
	return &logger{encoder: encoder}
}

func (l *logger) job(jobID string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.jobID = jobID
}

func (l *logger) info(message string) { l.write("info", message) }

func (l *logger) error(message string) { l.write("error", message) }

func (l *logger) write(level, message string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	_ = l.encoder.Encode(logLine{Level: level, Message: message, JobID: l.jobID})
}
