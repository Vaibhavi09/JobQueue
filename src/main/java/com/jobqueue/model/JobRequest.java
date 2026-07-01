package com.jobqueue.model;

/**
 * Shape of the JSON body accepted by POST /jobs. All fields except "name"
 * are optional and fall back to sane defaults in JobController.
 */
public class JobRequest {

    private String name;
    private String priority;
    private String taskType;
    private Long durationMs;
    private Double failureProbability;
    private Integer maxRetries;

    public String getName() {
        return name;
    }

    public String getPriority() {
        return priority;
    }

    public String getTaskType() {
        return taskType;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Double getFailureProbability() {
        return failureProbability;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }
}
