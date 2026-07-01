package com.jobqueue.util;

import com.jobqueue.model.Job;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates real work so the scheduler, retry logic, and cancellation can
 * all be exercised end-to-end without wiring up actual business logic.
 * Each TaskType maps to a different simulated workload.
 */
public final class TaskSimulator {

    private TaskSimulator() {
    }

    /** Runs the workload described by the job. Throws on simulated failure or interruption. */
    public static String execute(Job job) throws InterruptedException {
        switch (job.getTaskType()) {
            case NOOP:
                return "no-op";
            case SLEEP:
                Thread.sleep(job.getDurationMs());
                return "slept " + job.getDurationMs() + "ms";
            case COMPUTE:
                return busyCompute(job.getDurationMs());
            case RANDOM_FAIL:
                return randomFail(job);
            default:
                throw new IllegalStateException("Unknown task type: " + job.getTaskType());
        }
    }

    /** Burns CPU for approximately durationMs, checking for cancellation periodically. */
    private static String busyCompute(long durationMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + durationMs;
        long iterations = 0;
        long checksum = 1;
        while (System.currentTimeMillis() < deadline) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Compute task interrupted");
            }
            checksum = (checksum * 31 + iterations) % 1_000_000_007L;
            iterations++;
        }
        return "computed " + iterations + " iterations, checksum=" + checksum;
    }

    /** Does a little work, then fails at random with the job's configured probability. */
    private static String randomFail(Job job) throws InterruptedException {
        Thread.sleep(Math.min(job.getDurationMs(), 500));
        if (ThreadLocalRandom.current().nextDouble() < job.getFailureProbability()) {
            throw new RuntimeException("Simulated random failure (p=" + job.getFailureProbability() + ")");
        }
        return "succeeded despite failureProbability=" + job.getFailureProbability();
    }
}
