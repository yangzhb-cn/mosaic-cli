package com.yang.plan;

/** 计划任务在 DAG 执行过程中的生命周期状态。 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
