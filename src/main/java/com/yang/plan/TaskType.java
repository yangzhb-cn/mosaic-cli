package com.yang.plan;

/** Planner 可生成的任务类型，用于提示执行子 Agent 和控制写锁。 */
public enum TaskType {
    PLANNING,
    FILE_READ,
    FILE_WRITE,
    COMMAND,
    ANALYSIS,
    VERIFICATION
}
