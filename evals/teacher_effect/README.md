# 教师效果评测集

这个目录用于在修改 Context Select、Memory 召回、RAG 或教师提示词时做离线校准与回归。
评测把四个问题分开记录，避免用回答长度或提问次数代替教学效果：

- `select_memory.jsonl`：Select 双路相关性阈值、候选过滤和记忆选择。
- `rag_citation_quality.jsonl`：检索证据覆盖、引用准确性和无依据回答约束。
- `teaching_strategy_cases.jsonl`：讲解、诊断提问、纠错和用户选择适配。

每行是一个独立 JSON 对象，`case_id` 在整个子集中唯一。样本中的分数和期望行为是
评测标签，不应作为运行时上下文提供给被测模型。

## 使用方式

先检查数据结构并生成基线报告：

```bash
python evals/teacher_effect/evaluate.py
```

Select 阈值搜索只使用标记为 `calibration` 的样本；`regression` 样本只用于报告阈值的
泛化结果。RAG 和教学策略需要把系统输出整理为各自说明中约定的 prediction JSONL，
再交给同一脚本评分：

```bash
python evals/teacher_effect/evaluate.py \
  --rag-predictions /path/to/rag_predictions.jsonl \
  --teaching-predictions /path/to/teaching_predictions.jsonl
```

没有 prediction 文件时，脚本仍会校验全部样本，并输出 Select 阈值校准结果。评测集
不直接调用线上模型，不写 Memory、RAG 或聊天数据。

使用项目 `.env` 中配置的目标模型生成一轮基线：

```bash
python evals/teacher_effect/run_model_baseline.py \
  --output-dir evals/teacher_effect/runs/<run_name> \
  --temperature 0 \
  --max-tokens 1800
```

运行器保存 manifest、两个子集的逐条原始回答和 `manual_review.md`。中断后可用
`--resume` 续跑，已成功的 case 不会再次请求；当前模型端点建议保持 `--concurrency 1`。
只有没有可见文本且没有工具调用时才安全重试一次。运行器不会执行 Memory 或其他写工具。

RAG prediction 每行对应一个 case：

```json
{
  "case_id": "rag-citation-001",
  "citations": [
    {
      "chunk_id": "doc-ml:000003",
      "source": "机器学习入门讲义.md",
      "page": 4,
      "heading": "第一章 学习范式 / 监督学习"
    }
  ],
  "claim_citations": {"c1": ["doc-ml:000003"]},
  "satisfied_disclosures": [],
  "forbidden_assertions": []
}
```

`satisfied_disclosures` 和 `forbidden_assertions` 由人工或 Judge 从最终回答标注，取值必须
来自对应 case 的 Gold 字段。教学策略 prediction 使用相同的 `case_id`，并记录逐维评分
和观察到的提问行为：

```json
{
  "case_id": "teaching.explanation.beginner_concept.001",
  "rubric_scores": {
    "correctness_and_clarity": 2,
    "learner_adaptation": 2,
    "question_strategy": 2
  },
  "should_ask": false,
  "question_type": "none",
  "question_count": 0,
  "hard_failures": []
}
```

每个 prediction 文件必须覆盖相应子集的全部 `case_id`。`hard_failures` 记录该用例说明中
定义的严重违规；非空时该用例最高为 40 分。

## 指标解释

| 子集 | 主要指标 | 用途 |
|---|---|---|
| Select | candidate precision、recall、F1 | 选择 `task_threshold` 和 `response_threshold` |
| Memory | relevant recall、irrelevant rejection、scope safety | 检查相关记忆是否载入，以及过期、跨用户、跨会话内容是否被拒绝 |
| RAG | citation precision、required evidence recall、claim support | 区分“检索到了”与“答案真的被引用证据支持” |
| 教学策略 | rubric 各维得分与硬约束通过率 | 检查讲解、提问时机、问题质量、纠错和用户选择 |

阈值搜索结果只对当前样本和当前相关性打分器有效。替换 embedding、reranker 或记忆召回
策略后，应重新生成候选分数并校准。教学策略评分包含人工判断项，发布前应抽样复核，
不能把模型裁判分数当成真实学习增益。
