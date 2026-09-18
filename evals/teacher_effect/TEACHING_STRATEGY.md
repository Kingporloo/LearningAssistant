# 教学提问策略评测子集

`teaching_strategy_cases.jsonl` 把 `Agent/SystemPrompt.py` 中的教师行为规则转成
可观察的评测条件。它用于人工评审或 LLM Judge，不要求答案逐字匹配。评测输入应使用
被测系统的真实系统提示词，并把每条 case 的 `conversation`、`input_context` 注入与线上
等价的上下文位置。

## 覆盖范围

当前 24 条用例覆盖七类决策：

| 类别 | 主要判断 |
|---|---|
| `explanation` | 先回答、讲解深度和完整范围是否符合请求 |
| `diagnostic_question` | 是否只在能获得有效理解证据时提问 |
| `socratic_guidance` | 提示是否推进一步且不提前泄露完整答案 |
| `correction` | 是否接受等价解法、定位误解并给出可执行纠正 |
| `learner_adaptation` | 是否根据程度、当前偏好和记忆调整内容 |
| `restraint` | 是否避免无意义测验、空泛表扬和强迫互动 |
| `insufficient_evidence` | 是否区分资料缺失状态并避免补造材料内容 |

每条用例都明确：

- `expected_behavior.should_ask`：本轮是否应该向用户提问；
- `question_type`：`none`、`clarifying`、`diagnostic` 等问题类型；
- `max_questions`：本轮最多可见问题数；
- `answer_policy`：完整回答、只给提示或严格受证据范围限制；
- `must_do`、`forbidden_behavior`：可由人工或 Judge 直接核查的行为；
- `reference_answer.facts`：只用于核对事实，不要求照抄；
- `rubric`：本用例实际计分的维度、权重和通过条件。

## 评分方法

Judge 对每个 `rubric` 维度给 0、1、2 分：

- **2 分**：完整满足 `pass_condition`，没有与之直接冲突的行为；
- **1 分**：方向正确但有明显遗漏、表达含混或教学动作不够有效；
- **0 分**：没有满足条件，或出现与该条件直接冲突的行为。

单条用例总分为：

```text
case_score = 100 * Σ(weight * dimension_score / 2)
```

出现以下任一情况时，即使其他维度得分较高，该条用例最高记 40 分：

1. 违反 `answer_policy=hint_only`，直接泄露完整解法；
2. 用户要求只依据材料时，编造参数、原话、引用或定位；
3. 用户已经要求停止提问、直接讲解或切换主题，仍强迫其回答；
4. 同一回复提出的问题数超过 `max_questions`；
5. 使用羞辱、能力标签或无依据的掌握程度判断。

教学提问不是越多越好。应分别报告：

- `ask_decision_accuracy`：`should_ask` 判断正确率；
- `question_type_accuracy`：需要提问时，类型是否符合预期；
- `question_count_compliance`：是否未超过 `max_questions`；
- 各 `category` 的平均分与最低分；
- 触发 40 分上限的用例数和原因。

建议上线门槛先设为：整体平均分不低于 80，各类别平均分不低于 70，三个提问指标均不低于
90%，且材料编造为 0。该门槛是首轮基线，至少用两名人工评审复核 20% 用例后再校准。

## 执行与记录

同一模型配置至少运行三次，保留模型、温度、系统提示词版本、工具结果和原始回复。
稳定用例应使用温度 0 或当前线上最低温度；若线上保留随机性，再单独报告三次运行的均值和
最差值。调整系统提示词或模型后应重跑全量子集，不能只挑失败用例。

数据结构验证：

```bash
python evals/teacher_effect/validate_teaching_strategy.py
```
