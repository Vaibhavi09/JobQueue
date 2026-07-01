package com.jobqueue.queue;

import com.jobqueue.model.Job;
import com.jobqueue.model.Priority;
import com.jobqueue.model.TaskType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityJobQueueTest {

    private Job newJob(PriorityJobQueue queue, String name, Priority priority) {
        return new Job(name, name, priority, TaskType.NOOP, 0, 0.0, 0, queue.nextSequence());
    }

    @Test
    void higherPriorityIsDequeuedFirst() throws InterruptedException {
        PriorityJobQueue queue = new PriorityJobQueue();
        Job low = newJob(queue, "low", Priority.LOW);
        Job high = newJob(queue, "high", Priority.HIGH);
        Job medium = newJob(queue, "medium", Priority.MEDIUM);

        queue.submit(low);
        queue.submit(high);
        queue.submit(medium);

        assertEquals(high, queue.take());
        assertEquals(medium, queue.take());
        assertEquals(low, queue.take());
    }

    @Test
    void equalPriorityIsFifo() throws InterruptedException {
        PriorityJobQueue queue = new PriorityJobQueue();
        Job first = newJob(queue, "first", Priority.MEDIUM);
        Job second = newJob(queue, "second", Priority.MEDIUM);

        queue.submit(first);
        queue.submit(second);

        assertEquals(first, queue.take());
        assertEquals(second, queue.take());
    }

    @Test
    void listAllIncludesJobsRemovedFromPendingQueue() throws InterruptedException {
        PriorityJobQueue queue = new PriorityJobQueue();
        Job job = newJob(queue, "job", Priority.LOW);
        queue.submit(job);
        queue.take();

        assertTrue(queue.listAll().contains(job));
    }
}
