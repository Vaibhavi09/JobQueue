package com.jobqueue.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.jobqueue.model.Job;
import com.jobqueue.model.JobRequest;
import com.jobqueue.model.Priority;
import com.jobqueue.model.TaskType;
import com.jobqueue.queue.PriorityJobQueue;
import com.jobqueue.worker.WorkerPool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal REST layer for JobQueue, built on the JDK's built-in HTTP server
 * so the project has zero web-framework dependencies.
 *
 * Routes:
 *   POST   /jobs        submit a job
 *   GET    /jobs        list all jobs
 *   GET    /jobs/{id}   fetch one job's status
 *   DELETE /jobs/{id}   cancel a job
 *   GET    /health      liveness check
 */
public class JobController {

    private final HttpServer server;
    private final PriorityJobQueue queue;
    private final WorkerPool workerPool;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public JobController(int port, PriorityJobQueue queue, WorkerPool workerPool) throws IOException {
        this.queue = queue;
        this.workerPool = workerPool;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/jobs", this::handleJobs);
        this.server.createContext("/health", exchange -> sendJson(exchange, 200, Map.of("status", "ok")));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void handleJobs(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            // "/jobs" -> ["", "jobs"]   "/jobs/{id}" -> ["", "jobs", "{id}"]
            String[] segments = path.split("/");

            if (segments.length == 2) {
                if ("POST".equalsIgnoreCase(method)) {
                    handleSubmit(exchange);
                } else if ("GET".equalsIgnoreCase(method)) {
                    handleList(exchange);
                } else {
                    sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                }
            } else if (segments.length == 3) {
                String id = segments[2];
                if ("GET".equalsIgnoreCase(method)) {
                    handleGet(exchange, id);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    handleCancel(exchange, id);
                } else {
                    sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                }
            } else {
                sendJson(exchange, 404, Map.of("error", "Not found"));
            }
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    private void handleSubmit(HttpExchange exchange) throws IOException {
        JobRequest req;
        try {
            req = gson.fromJson(readBody(exchange), JobRequest.class);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, Map.of("error", "Malformed JSON body"));
            return;
        }

        if (req == null || isBlank(req.getName())) {
            sendJson(exchange, 400, Map.of("error", "\"name\" is required"));
            return;
        }

        Priority priority;
        try {
            priority = req.getPriority() == null ? Priority.MEDIUM : Priority.valueOf(req.getPriority().toUpperCase());
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "priority must be one of HIGH, MEDIUM, LOW"));
            return;
        }

        TaskType taskType;
        try {
            taskType = req.getTaskType() == null ? TaskType.SLEEP : TaskType.valueOf(req.getTaskType().toUpperCase());
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "taskType must be one of NOOP, SLEEP, COMPUTE, RANDOM_FAIL"));
            return;
        }

        long durationMs = req.getDurationMs() == null ? 2000 : req.getDurationMs();
        double failureProbability = req.getFailureProbability() == null ? 0.0 : req.getFailureProbability();
        int maxRetries = req.getMaxRetries() == null ? 3 : req.getMaxRetries();

        if (durationMs < 0 || failureProbability < 0 || failureProbability > 1 || maxRetries < 0) {
            sendJson(exchange, 400, Map.of("error",
                    "durationMs/maxRetries must be >= 0 and failureProbability must be between 0 and 1"));
            return;
        }

        String id = UUID.randomUUID().toString();
        Job job = new Job(id, req.getName(), priority, taskType, durationMs, failureProbability, maxRetries,
                queue.nextSequence());
        queue.submit(job);
        System.out.printf("[API] submitted job=%s name=%s priority=%s taskType=%s%n",
                id, job.getName(), priority, taskType);
        sendJson(exchange, 201, job);
    }

    private void handleList(HttpExchange exchange) throws IOException {
        List<Job> jobs = queue.listAll();
        sendJson(exchange, 200, jobs);
    }

    private void handleGet(HttpExchange exchange, String id) throws IOException {
        Job job = queue.get(id);
        if (job == null) {
            sendJson(exchange, 404, Map.of("error", "No job with id " + id));
            return;
        }
        sendJson(exchange, 200, job);
    }

    private void handleCancel(HttpExchange exchange, String id) throws IOException {
        Job job = queue.get(id);
        if (job == null) {
            sendJson(exchange, 404, Map.of("error", "No job with id " + id));
            return;
        }
        synchronized (job) {
            switch (job.getStatus()) {
                case PENDING:
                    queue.removeFromPending(job);
                    job.markCancelled();
                    System.out.printf("[API] cancelled pending job=%s%n", id);
                    sendJson(exchange, 200, job);
                    break;
                case RUNNING:
                    job.requestCancel();
                    workerPool.cancelRunningJob(id);
                    System.out.printf("[API] requested cancellation of running job=%s%n", id);
                    sendJson(exchange, 202, job);
                    break;
                default:
                    sendJson(exchange, 409, Map.of("error", "Job already in terminal state: " + job.getStatus()));
            }
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
