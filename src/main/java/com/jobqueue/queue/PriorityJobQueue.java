package com.jobqueue.queue;

import com.jobqueue.model.Job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe priority queue backing the scheduler. A PriorityBlockingQueue
 * orders pending jobs by priority (HIGH first) and, within the same
 * priority, by submission order (FIFO). Alongside it, a ConcurrentHashMap
 * indexes every job ever submitted by id, so the REST API can answer
 * status lookups even after a job has left the queue (completed, failed,
 * or been cancelled).
 */
public class PriorityJobQueue {

    private static final int INITIAL_CAPACITY = 64;

    private final PriorityBlockingQueue<Job> pending;
    private final ConcurrentHashMap<String, Job> jobsById = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong();

    public PriorityJobQueue() {
        Comparator<Job> byPriorityThenFifo = Comparator
                .comparingInt((Job job) -> job.getPriority().getWeight())
                .reversed()
                .thenComparingLong(Job::getSequence);
        this.pending = new PriorityBlockingQueue<>(INITIAL_CAPACITY, byPriorityThenFifo);
    }

    public long nextSequence() {
        return sequenceGenerator.incrementAndGet();
    }

    /** Registers a brand-new job and makes it eligible for pickup. */
    public void submit(Job job) {
        jobsById.put(job.getId(), job);
        pending.offer(job);
    }

    /** Re-enters an existing job into the queue for a retry attempt. */
    public void requeue(Job job) {
        job.setSequence(nextSequence());
        pending.offer(job);
    }

    /** Blocks until a job is available. */
    public Job take() throws InterruptedException {
        return pending.take();
    }

    public Job get(String id) {
        return jobsById.get(id);
    }

    /** All jobs ever submitted, oldest first. */
    public List<Job> listAll() {
        List<Job> jobs = new ArrayList<>(jobsById.values());
        jobs.sort(Comparator.comparingLong(Job::getCreatedAt));
        return jobs;
    }

    /** Removes a job that hasn't been picked up yet (used by DELETE /jobs/{id}). */
    public boolean removeFromPending(Job job) {
        return pending.remove(job);
    }

    public int pendingSize() {
        return pending.size();
    }
}
