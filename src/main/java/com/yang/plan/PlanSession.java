package com.yang.plan;

/** 保存 CLI 当前 plan 状态和内存中的待执行计划。 */
public final class PlanSession {
    /** CLI plan 流程的当前阶段。 */
    public enum State {
        ACT,
        AWAITING_TASK,
        PLAN_READY,
        EXECUTING
    }

    private State state = State.ACT;
    private ExecutionPlan plan;

    public State state() {
        return state;
    }

    public ExecutionPlan plan() {
        return plan;
    }

    public boolean isActive() {
        return state != State.ACT;
    }

    public boolean awaitingTask() {
        return state == State.AWAITING_TASK;
    }

    public boolean ready() {
        return state == State.PLAN_READY && plan != null;
    }

    public void awaitTask() {
        plan = null;
        state = State.AWAITING_TASK;
    }

    public void setPlan(ExecutionPlan plan) {
        this.plan = plan;
        this.state = State.PLAN_READY;
    }

    public void executing() {
        state = State.EXECUTING;
    }

    public void finishExecuting() {
        clear();
    }

    public void clear() {
        plan = null;
        state = State.ACT;
    }
}
