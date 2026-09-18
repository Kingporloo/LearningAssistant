# Select 与记忆选择评测子集

`select_memory.jsonl` 用于校准 Context Select 的任务相关性门槛、回答方式相关性门槛，并回归验证记忆选择规则。每行是一个独立 case，可直接映射到现有 `ContextUnit` 和 `select(...)`。

每个 case 固定包含：

- `case_id`：稳定且唯一的样本标识。
- `split`：`calibration` 用于网格选阈值，`regression` 只验证选择契约。
- `scope`：当前用户、会话和评测时间。
- `query`：任务查询、可选的回答偏好查询，以及用于确定性覆盖选择的向量。
- `select_config`：本 case 使用的预算和门槛。
- `candidates`：候选正文、类型、来源、状态、importance、`r_task`、`r_response` 和人工相关性标签。
- `expected_selected_ids`：按候选原顺序给出的预期选择结果。
- `expected_rejected`：所有未选候选及现有 Selection Trace 应记录的原因。

当前子集覆盖任务与回答方式相关性边界、来源和显式去重、importance、跨会话长期记忆、工作记忆作用域与 TTL、情景记忆时效、限定场景偏好、跨用户隔离，以及已删除、被纠正、版本失效和正文缺失的记忆。

执行确定性回归及 0～1、步长 0.01 的门槛网格校准：

```bash
python evals/teacher_effect/validate_select_memory.py --calibrate
```

校准只使用 `split=calibration` 且 `ground_truth.task_relevant` 或 `ground_truth.response_relevant` 非空的候选；`regression` 与 Hard Gate 专用样本不会参与阈值选择。当前数据是代码规则与边界行为的首版基线。接入真实 Reranker 后，应保留人工标签并替换候选分数，再用独立 holdout 按教师最终回答质量复核门槛，不把这批小样本的数值视为生产结论。
