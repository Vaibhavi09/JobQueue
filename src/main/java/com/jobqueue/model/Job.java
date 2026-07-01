package com.jobqueue.model;

/**
 * A unit of work submitted to the scheduler.
 *
 * A Job carries everything the worker pool needs to execute it (task type,
 * simulated duration, failure probability, retry budget) plus everything
 * the REST API needs to report on it (status, timestamps, retry count,
 * error message). State transitions are synchronized so that the API
 * thread (handling a cancel request) and the worker thread (finishing the
 * job) can never race each other into an inconsistent state.
 */
public class Job {

    private final String id;
    private final String name;
    private final Priority priority;
    private final TaskType taskType;
    private final long durationMs;
    private final double failureProbability;
    private final int maxRetries;
    private final long createdAt;

    private volatile JobStatus status;
    private volatile int retryCount;
    private volatile Long startedAt;
    private volatile Long completedAt;
    private volatile String errorMessage;
    private volatile String result;
    private volatile boolean cancelRequested;

    // Tie-breaker for FIFO ordering within the same priority. Not part of
    // the public API, so it's excluded from JSON serialization.
    private transient long sequence;

    public Job(String id, String name, Priority priority, TaskType taskType,
               long durationMs, double failureProbability, int maxRetries, long sequence) {
        this.id = id;
        this.name = name;
        this.priority = priority;
        this.taskType = taskType;
        this.durationMs = durationMs;
        this.failureProbability = failureProbability;
        this.maxRetries = maxRetries;
        this.sequence = sequence;
        this.createdAt = System.currentTimeMillis();
        this.status = JobStatus.PENDING;
        this.retryCount = 0;
    }

    // lifecycle transitions

    public synchronized void markRunning() {
        this.status = JobStatus.RUNNING;
        this.startedAt = System.currentTimeMillis();
    }

    public synchronized void markCompleted(String result) {
        this.status = JobStatus.COMPLETED;
        this.result = result;
        this.completedAt = System.currentTimeMillis();
    }

    public synchronized void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = System.currentTimeMillis();
    }

    public synchronized void markCancelled() {
        this.status = JobStatus.CANCELLED;
        this.completedAt = System.currentTimeMillis();
    }

    /** Puts the job back in PENDING so it can be requeued for a retry attempt. */
    public synchronized void markPendingForRetry() {
        this.status = JobStatus.PENDING;
        this.startedAt = null;
    }

    public synchronized int incrementRetryCount() {
        return ++retryCount;
    }

    /** Cooperative cancellation flag checked by the worker after an interrupt. */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    // getters / setters

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Priority getPriority() {
        return priority;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public double getFailureProbability() {
        return failureProbability;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public Long getCompletedAt() {
        return completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getResult() {
        return result;
    }

    public long getSequence() {
        return sequence;
    }

    /** Bumped on every requeue so a retried job re-enters the queue behind
     *  jobs of the same priority that are already waiting. */
    public void setSequence(long sequence) {
        this.sequence = sequence;
    }
}
