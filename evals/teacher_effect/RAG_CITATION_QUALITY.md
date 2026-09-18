# RAG 引用质量评测子集

`rag_citation_quality.jsonl` 使用当前 RAG 查询实际返回的字段构造固定评测输入：
`chunk_id`、`document_id`、`text`、`source`、`file_type`、`page`、三级标题、
`similarity`、`rrf_score` 和 `routes`。其中 `chunk_id` 是自动计分的权威引用键；
文件名、页码和标题用于检查面向用户的来源定位是否真实。

每行是一个独立 case。`answer_scope` 区分只能依据材料回答，还是可以在声明依据范围后
使用一般知识。`gold.claims` 给出需要判断的最小事实单元。全局引用标签含义如下：

- `required_citation_ids`：完整回答必须引用的证据。
- `acceptable_citation_ids`：引用后不降低精确率，但不是完成回答的必要证据。
- `forbidden_citation_ids`：相似干扰、错误版本或与当前结论不相干的证据。
- `required_disclosures`：无结果、证据不足、使用一般知识或缺少页码时必须说明的边界。
- `forbidden_assertions`：不能从当前证据推出的关键结论。

建议要求被测模型额外输出 claim 到 `chunk_id` 的结构化映射，面向用户的正文仍可使用
“文件名 + 页码/标题”表达。基于结构化映射计算：

1. **Citation Precision** = 已引用的 required/acceptable ID 数 ÷ 全部引用 ID 数。
2. **Citation Recall** = 已引用的 required ID 数 ÷ required ID 总数。
3. **Forbidden Citation Rate** = 已引用 forbidden ID 数 ÷ 全部引用 ID 数。
4. **Claim Support Accuracy**：逐项判断回答 claim 是否与证据一致；`insufficient` 不得被补造成确定事实。
5. **Location Accuracy**：引用的文件名、页码和标题必须与 `citation_locations` 一致；`page=null`
   时不得编造页码。
6. **Boundary Disclosure Recall**：应披露的检索状态、证据缺口和一般知识使用范围是否全部出现。

引用 Precision、Recall 和事实正确性应分别报告，不能用一个加权总分掩盖“答案正确但引用错误”
或“引用正确但结论超出证据”的问题。推荐发布门槛：Citation Precision ≥ 0.95、Citation Recall
≥ 0.90、Forbidden Citation Rate = 0、材料限定题的无依据断言率 = 0。首次真实模型运行后再按失败分布调整门槛。

执行数据校验：

```bash
python evals/teacher_effect/validate_rag_citation_quality.py
```

校验会检查稳定 ID、查询响应状态、候选字段、引用标签互斥与全覆盖、claim 引用归属，以及
正确引用、跨页标题继承、无结果、相似干扰、错误来源/页码、证据不足和证据支持回答等必需覆盖。
