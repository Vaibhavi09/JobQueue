package com.jobqueue.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobTest {

    private Job newJob() {
        return new Job("id-1", "test-job", Priority.HIGH, TaskType.NOOP, 100, 0.0, 3, 1);
    }

    @Test
    void startsPending() {
        Job job = newJob();
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(0, job.getRetryCount());
    }

    @Test
    void markRunningSetsStartedAt() {
        Job job = newJob();
        job.markRunning();
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertTrue(job.getStartedAt() > 0);
    }

    @Test
    void markCompletedStoresResult() {
        Job job = newJob();
        job.markRunning();
        job.markCompleted("done");
        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertEquals("done", job.getResult());
        assertTrue(job.getCompletedAt() > 0);
    }

    @Test
    void markPendingForRetryClearsStartedAtAndKeepsRetryCount() {
        Job job = newJob();
        job.markRunning();
        job.incrementRetryCount();
        job.markPendingForRetry();
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertNull(job.getStartedAt());
        assertEquals(1, job.getRetryCount());
    }

    @Test
    void requestCancelSetsFlag() {
        Job job = newJob();
        assertFalse(job.isCancelRequested());
        job.requestCancel();
        assertTrue(job.isCancelRequested());
    }
}
