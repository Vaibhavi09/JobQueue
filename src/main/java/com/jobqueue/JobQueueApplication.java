package com.jobqueue;

import com.jobqueue.api.JobController;
import com.jobqueue.queue.PriorityJobQueue;
import com.jobqueue.worker.WorkerPool;

/**
 * Entry point. Wires the queue, worker pool, and REST API together and
 * keeps the process alive (the JDK HttpServer's internal dispatch thread
 * is non-daemon) until it receives a shutdown signal.
 */
public class JobQueueApplication {

    public static void main(String[] args) throws Exception {
        int port = intEnv("JOBQUEUE_PORT", 8080);
        int poolSize = intEnv("JOBQUEUE_WORKERS", 4);

        PriorityJobQueue queue = new PriorityJobQueue();
        WorkerPool workerPool = new WorkerPool(queue, poolSize);
        JobController controller = new JobController(port, queue, workerPool);

        workerPool.start();
        controller.start();

        System.out.println("======================================================");
        System.out.println(" JobQueue is running");
        System.out.println(" REST API:       http://localhost:" + port + "/jobs");
        System.out.println(" Worker threads: " + poolSize);
        System.out.println("======================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[JobQueue] shutting down...");
            controller.stop();
            workerPool.shutdown();
        }));
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
