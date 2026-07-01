# JobQueue - Distributed Task Scheduler

![Java](https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk)
![Maven](https://img.shields.io/badge/Build-Maven-C71A36?logo=apachemaven)
![Concurrency](https://img.shields.io/badge/Concurrency-Multithreaded-blue)
![REST](https://img.shields.io/badge/API-REST-green)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

A fault-tolerant, in-memory task scheduler with a priority queue, a
concurrent worker pool, automatic retries with backoff, and a REST API -
built with plain Java (no framework) to show the concurrency and networking
primitives directly.

## Overview

JobQueue accepts jobs over HTTP, holds them in a priority queue (HIGH →
MEDIUM → LOW, FIFO within a priority tier), and hands them out to a fixed
pool of worker threads. Each job simulates real work (sleep, CPU-bound
compute, or a randomly-failing task) so the full lifecycle - submission,
execution, failure, retry, completion, and cancellation - can be exercised
without any external dependencies.

```
 POST /jobs                     ┌─────────────────┐
  ───────────────►  API layer   │ PriorityJobQueue │  (HIGH > MEDIUM > LOW, FIFO within tier)
                    (HttpServer)└─────────────────┘
                          │               │  take()
                          │ status lookup │
                          ▼               ▼
                    ┌───────────┐   ┌─────────────┐
                    │ jobsById  │   │ WorkerPool  │──► worker-1..N threads
                    │  (map)    │   │             │      │
                    └───────────┘   └─────────────┘      ▼
                                           │        TaskSimulator (SLEEP / COMPUTE / RANDOM_FAIL)
                                           │              │
                                    on failure       success / failure
                                           ▼              │
                                 retryScheduler ──requeue──┘  (linear backoff, up to maxRetries)
```

## Features

- **Priority scheduling** - `HIGH` / `MEDIUM` / `LOW`, FIFO within the same tier.
- **Concurrent worker pool** - configurable number of daemon threads pulling from the queue.
- **Retry with backoff** - failed jobs are retried up to a per-job `maxRetries`, with linear backoff between attempts.
- **Cooperative cancellation** - `DELETE /jobs/{id}` removes a pending job outright, or interrupts the worker thread if the job is already running.
- **Zero external infra** - everything is in-memory; no database, no message broker.
- **Structured console logging** of every state transition (submitted, running, retrying, completed, failed, cancelled).

## Tech Stack

- **Java 17+**, `java.util.concurrent` (`PriorityBlockingQueue`, `ExecutorService`, `ScheduledExecutorService`)
- **`com.sun.net.httpserver.HttpServer`** - the JDK's built-in HTTP server (no Spring/Jetty dependency)
- **Gson** - the one external dependency, used for JSON (de)serialization
- **Maven** - build and dependency management
- **JUnit 5** - unit tests for the queue and job lifecycle

## Getting Started

### Prerequisites

- Java 17 or newer
- Maven 3.8+

### Build & run

```bash
cd jobqueue
mvn clean package
java -jar target/jobqueue.jar
```

You should see:

```
[WorkerPool] started with 4 worker thread(s)
======================================================
 JobQueue is running
 REST API:       http://localhost:8080/jobs
 Worker threads: 4
======================================================
```

### Configuration

Both are optional environment variables:

| Variable          | Default | Description                        |
|--------------------|---------|-------------------------------------|
| `JOBQUEUE_PORT`     | `8080`  | HTTP port the API listens on        |
| `JOBQUEUE_WORKERS`  | `4`     | Number of worker threads in the pool |

```bash
JOBQUEUE_PORT=9090 JOBQUEUE_WORKERS=8 java -jar target/jobqueue.jar
```

### Run tests

```bash
mvn test
```

## API Reference

| Method | Path         | Description             |
|--------|--------------|--------------------------|
| POST   | `/jobs`      | Submit a new job         |
| GET    | `/jobs`      | List all jobs            |
| GET    | `/jobs/{id}` | Get one job's status     |
| DELETE | `/jobs/{id}` | Cancel a job              |
| GET    | `/health`    | Liveness check            |

### Job submission body

```json
{
  "name": "resize-image-batch",
  "priority": "HIGH",
  "taskType": "COMPUTE",
  "durationMs": 3000,
  "failureProbability": 0.0,
  "maxRetries": 3
}
```

All fields except `name` are optional:

| Field                | Default  | Values                                  |
|-----------------------|----------|-------------------------------------------|
| `priority`            | `MEDIUM` | `HIGH`, `MEDIUM`, `LOW`                    |
| `taskType`            | `SLEEP`  | `NOOP`, `SLEEP`, `COMPUTE`, `RANDOM_FAIL`  |
| `durationMs`          | `2000`   | simulated work duration in milliseconds    |
| `failureProbability`  | `0.0`    | 0-1, only used by `RANDOM_FAIL`            |
| `maxRetries`          | `3`      | retry attempts before a job is marked FAILED |

Once a job's retry count exceeds `maxRetries`, it is marked `FAILED`
permanently and is not requeued again.

### Example: submit a job

```bash
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{
        "name": "generate-report",
        "priority": "HIGH",
        "taskType": "COMPUTE",
        "durationMs": 2000
      }'
```

Response (`201 Created`):

```json
{
  "id": "3f1c9d2e-...",
  "name": "generate-report",
  "priority": "HIGH",
  "taskType": "COMPUTE",
  "durationMs": 2000,
  "failureProbability": 0.0,
  "maxRetries": 3,
  "createdAt": 1751000000000,
  "status": "PENDING",
  "retryCount": 0
}
```

### Example: check status

```bash
curl http://localhost:8080/jobs/3f1c9d2e-...
```

### Example: list all jobs

```bash
curl http://localhost:8080/jobs
```

### Example: cancel a job

```bash
curl -X DELETE http://localhost:8080/jobs/3f1c9d2e-...
```

- `200 OK` - job was pending and has been cancelled outright.
- `202 Accepted` - job was running; cancellation was requested and the worker thread was interrupted.
- `409 Conflict` - job already finished (completed / failed / cancelled).

### Example: simulate a failing job with retries

```bash
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{
        "name": "flaky-upload",
        "taskType": "RANDOM_FAIL",
        "failureProbability": 0.7,
        "maxRetries": 4
      }'
```

Watch the console output to see retries happen with increasing backoff.

## Project Structure

```
jobqueue/
  src/main/java/com/jobqueue/
    model/       Job, JobRequest, Priority, JobStatus, TaskType
    queue/       PriorityJobQueue (thread-safe priority queue + id index)
    worker/      WorkerPool (worker threads, retries, cancellation)
    util/        TaskSimulator (simulated workloads)
    api/         JobController (REST endpoints)
    JobQueueApplication.java (entry point)
  src/test/java/com/jobqueue/   unit tests
  pom.xml
```

## License

MIT
