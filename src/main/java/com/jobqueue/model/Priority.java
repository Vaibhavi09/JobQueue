package com.jobqueue.model;

/**
 * Job priority. Higher weight is dequeued first; jobs of equal priority
 * are served in FIFO order (see PriorityJobQueue).
 */
public enum Priority {
    HIGH(3),
    MEDIUM(2),
    LOW(1);

    private final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}
