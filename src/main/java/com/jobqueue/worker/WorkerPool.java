package com.jobqueue.worker;

import com.jobqueue.model.Job;
import com.jobqueue.model.JobStatus;
import com.jobqueue.queue.PriorityJobQueue;
import com.jobqueue.util.TaskSimulator;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-size pool of worker threads that continuously pull jobs off the
 * priority queue and run them through TaskSimulator. Failed jobs get
 * retried with linear backoff up to the job's maxRetries before being
 * marked permanently FAILED. If a cancel request comes in for a job
 * that's already RUNNING, the worker thread executing it gets interrupted
 * so the job stops promptly instead of running to completion.
 */
public class WorkerPool {

    private static final long RETRY_BASE_DELAY_MS = 500;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final PriorityJobQueue queue;
    private final int poolSize;
    private final ExecutorService workers;
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> namedDaemonThread(r, "retry-scheduler"));
    private final ConcurrentHashMap<String, Thread> runningThreads = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public WorkerPool(PriorityJobQueue queue, int poolSize) {
        this.queue = queue;
        this.poolSize = poolSize;
        this.workers = Executors.newFixedThreadPool(poolSize, r -> namedDaemonThread(r, "worker"));
    }

    private static Thread namedDaemonThread(Runnable r, String prefix) {
        Thread t = new Thread(r, prefix + "-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    }

    public void start() {
        for (int i = 0; i < poolSize; i++) {
            workers.submit(this::workerLoop);
        }
        System.out.printf("[WorkerPool] started with %d worker thread(s)%n", poolSize);
    }

    private void workerLoop() {
        while (running) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                continue; // running flag is re-checked at the top of the loop
            }
            if (job.getStatus() != JobStatus.PENDING) {
                continue; // cancelled while it was still waiting in the queue
            }
            runJob(job);
        }
    }

    private void runJob(Job job) {
        String threadName = Thread.currentThread().getName();
        runningThreads.put(job.getId(), Thread.currentThread());
        job.markRunning();
        log(threadName, job, "RUNNING (attempt " + (job.getRetryCount() + 1) + ")");
        try {
            String result = TaskSimulator.execute(job);
            job.markCompleted(result);
            log(threadName, job, "COMPLETED - " + result);
        } catch (InterruptedException interrupted) {
            Thread.interrupted(); // clear the flag before this thread picks up more work
            if (job.isCancelRequested()) {
                job.markCancelled();
                log(threadName, job, "CANCELLED");
            } else {
                job.markFailed("Interrupted during shutdown");
                log(threadName, job, "FAILED (interrupted during shutdown)");
            }
        } catch (Exception e) {
            handleFailure(job, e, threadName);
        } finally {
            runningThreads.remove(job.getId());
        }
    }

    private void handleFailure(Job job, Exception e, String threadName) {
        int attempt = job.incrementRetryCount();
        if (attempt <= job.getMaxRetries()) {
            long backoffMs = RETRY_BASE_DELAY_MS * attempt;
            job.markPendingForRetry();
            log(threadName, job, "FAILED, scheduling retry " + attempt + "/" + job.getMaxRetries()
                    + " in " + backoffMs + "ms (" + e.getMessage() + ")");
            retryScheduler.schedule(() -> queue.requeue(job), backoffMs, TimeUnit.MILLISECONDS);
        } else {
            job.markFailed(e.getMessage());
            log(threadName, job, "FAILED permanently after " + job.getMaxRetries()
                    + " retries (" + e.getMessage() + ")");
        }
    }

    /** Interrupts the worker thread currently executing {@code jobId}, if any. */
    public boolean cancelRunningJob(String jobId) {
        Thread t = runningThreads.get(jobId);
        if (t == null) {
            return false;
        }
        t.interrupt();
        return true;
    }

    public void shutdown() {
        running = false;
        workers.shutdownNow();
        retryScheduler.shutdownNow();
    }

    private static void log(String threadName, Job job, String message) {
        System.out.printf("[%s] [%s] job=%s (%s) %s%n",
                Instant.now(), threadName, job.getId(), job.getName(), message);
    }
}
