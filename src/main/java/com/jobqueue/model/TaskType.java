package com.jobqueue.model;

/**
 * The kind of simulated work a job performs. JobQueue ships with a
 * built-in simulator (see com.jobqueue.util.TaskSimulator) so the scheduler,
 * retry logic, and cancellation can be exercised end-to-end without wiring
 * up real business logic.
 */
public enum TaskType {
    /** Completes instantly. */
    NOOP,
    /** Sleeps for durationMs, then completes. */
    SLEEP,
    /** Burns CPU for approximately durationMs, then completes. */
    COMPUTE,
    /** Sleeps briefly, then fails with probability failureProbability. */
    RANDOM_FAIL
}
