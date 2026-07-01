package com.jobqueue.model;

/** Lifecycle states a Job moves through, in order. */
public enum JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
