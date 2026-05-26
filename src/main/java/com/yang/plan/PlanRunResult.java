package com.yang.plan;

/** 表示一次 DAG 执行的最终成功状态和失败原因。 */
public record PlanRunResult(boolean success, String failure) {
}
