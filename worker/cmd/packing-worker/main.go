package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/client"
	sdklog "go.temporal.io/sdk/log"
	"go.temporal.io/sdk/worker"

	"github.com/Peczenko/3d-packing-backend/worker/internal/azureio"
	"github.com/Peczenko/3d-packing-backend/worker/internal/config"
	"github.com/Peczenko/3d-packing-backend/worker/internal/contracts"
	"github.com/Peczenko/3d-packing-backend/worker/internal/engine"
	"github.com/Peczenko/3d-packing-backend/worker/internal/pipeline"
	"github.com/Peczenko/3d-packing-backend/worker/internal/resultobj"
)

const (
	exitClean          = 0
	exitInfrastructure = 1
	exitConfiguration  = 2
)

const (
	dialRetryInterval = 5 * time.Second
	workerStopTimeout = 20 * time.Second
)

type runner interface {
	Run(context.Context) error
}

type connector func(context.Context, config.Config, *slog.Logger) (runner, error)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	code := run(ctx, os.Args[1:], os.Stdout)
	stop()
	os.Exit(code)
}

func run(ctx context.Context, args []string, out io.Writer) int {
	return runWith(ctx, args, out, connectTemporal)
}

func runWith(ctx context.Context, args []string, out io.Writer, connect connector) int {
	log := slog.New(slog.NewJSONHandler(out, nil))

	if len(args) > 0 {
		log.Error("packing-worker takes no arguments", "count", len(args))
		return exitConfiguration
	}

	cfg, err := config.Load()
	if err != nil {
		log.Error("configuration is unusable", "error", err.Error())
		return exitConfiguration
	}

	w, err := connect(ctx, cfg, log)
	if err != nil {
		if ctx.Err() != nil {
			log.Info("shutdown requested before the worker connected")
			return exitClean
		}
		log.Error("worker could not be built", "error", err.Error())
		return exitConfiguration
	}

	log.Info("worker polling", "address", cfg.TemporalAddress, "namespace", cfg.TemporalNamespace, "taskQueue", cfg.TaskQueue,
		"resultTaskQueue", cfg.ResultTaskQueue)
	if err := w.Run(ctx); err != nil {
		log.Error("worker stopped", "error", err.Error())
		return exitInfrastructure
	}
	log.Info("worker stopped")
	return exitClean
}

func connectTemporal(ctx context.Context, cfg config.Config, log *slog.Logger) (runner, error) {
	blobClient, err := azureio.NewBlobClient(cfg)
	if err != nil {
		return nil, err
	}
	c, err := dial(ctx, cfg, log)
	if err != nil {
		return nil, err
	}

	options := worker.Options{
		MaxConcurrentActivityExecutionSize: 1,
		WorkerStopTimeout:                  workerStopTimeout,
	}
	engineWorker := worker.New(c, cfg.TaskQueue, options)
	resultWorker := worker.New(c, cfg.ResultTaskQueue, options)
	act := pipeline.NewActivity(
		azureio.NewBlobArtifacts(blobClient, cfg),
		engine.NewRunner(cfg.PackerPath, cfg.PackerVersion, cfg.OverrunGrace),
		resultobj.Writer{},
		c,
		cfg.WorkRoot,
		cfg.HeartbeatInterval,
	)
	engineWorker.RegisterActivityWithOptions(act.RunEngine, activity.RegisterOptions{Name: contracts.RunEngineActivity})
	resultWorker.RegisterActivityWithOptions(act.WriteResult, activity.RegisterOptions{Name: contracts.WriteResultActivity})
	return &temporalWorker{client: c, engine: engineWorker, result: resultWorker}, nil
}

func dial(ctx context.Context, cfg config.Config, log *slog.Logger) (client.Client, error) {
	for {
		c, err := client.DialContext(ctx, client.Options{
			HostPort:  cfg.TemporalAddress,
			Namespace: cfg.TemporalNamespace,
			Logger:    sdklog.NewStructuredLogger(log),
		})
		if err == nil {
			return c, nil
		}
		log.Warn("temporal is not reachable yet", "address", cfg.TemporalAddress, "error", err.Error())
		select {
		case <-ctx.Done():
			return nil, errors.Join(ctx.Err(), err)
		case <-time.After(dialRetryInterval):
		}
	}
}

type temporalWorker struct {
	client client.Client
	engine worker.Worker
	result worker.Worker
}

func (t *temporalWorker) Run(ctx context.Context) error {
	defer t.client.Close()
	if err := t.result.Start(); err != nil {
		return err
	}
	defer t.result.Stop()
	interrupt := make(chan any)
	go func() {
		<-ctx.Done()
		close(interrupt)
	}()
	return t.engine.Run(interrupt)
}
