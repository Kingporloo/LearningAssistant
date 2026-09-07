"""语义记忆的结构化提炼，不承担图数据库写入。"""

from __future__ import annotations

import json
import os
import re
from threading import Lock
from typing import Any, Callable

EXTRACTION_PROMPT = """从用户明确陈述的记忆内容中提取实体和关系。
只输出 JSON：
{{"entities":[{{"name":"实体名","type":"类型"}}],
  "relations":[{{"subject":"实体名","relation":"关系","object":"实体名"}}]}}
不得补充原文没有的信息。没有可靠关系时返回空数组。

记忆内容：{content}"""


class SemanticProcessor:
    """可注入提取器；默认仅在配置模型时懒加载 LLM。"""

    def __init__(self, extractor: Callable[[str], dict[str, Any]] | None = None) -> None:
        self._extractor = extractor
        self._llm = None
        self._lock = Lock()

    def prepare(
        self,
        content: str,
        *,
        source_session_id: str,
        source_message_id: str | None,
    ) -> dict[str, Any]:
        graph, graph_status = self._extract(content)
        return {
            "content": content,
            "graph": graph,
            "graph_status": graph_status,
            "source": {
                "session_id": source_session_id,
                "message_id": source_message_id,
            },
        }

    def _extract(self, content: str) -> tuple[dict[str, Any], str]:
        if self._extractor is not None:
            return _valid_graph(self._extractor(content)), "ok"
        if not os.getenv("AGENT_MODEL") or not os.getenv("AGENT_API_KEY"):
            return {"entities": [], "relations": []}, "skipped"
        try:
            response = self._model().invoke(EXTRACTION_PROMPT.format(content=content))
            return _valid_graph(_parse_json(str(response.content))), "ok"
        except Exception:
            return {"entities": [], "relations": []}, "error"

    def _model(self):
        if self._llm is None:
            with self._lock:
                if self._llm is None:
                    from langchain_openai import ChatOpenAI

                    self._llm = ChatOpenAI(
                        model=os.environ["AGENT_MODEL"],
                        api_key=os.environ["AGENT_API_KEY"],
                        base_url=os.getenv("AGENT_BASE_URL") or None,
                        temperature=0,
                        timeout=60,
                    )
        return self._llm


def _parse_json(text: str) -> dict[str, Any]:
    text = re.sub(r"```(?:json)?", "", text).strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return {}
    try:
        value = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return {}
    return value if isinstance(value, dict) else {}


def _valid_graph(value: dict[str, Any]) -> dict[str, Any]:
    entities = []
    for item in value.get("entities") or []:
        if not isinstance(item, dict) or not str(item.get("name", "")).strip():
            continue
        entities.append({
            "name": str(item["name"]).strip(),
            "type": str(item.get("type", "")).strip(),
        })
    names = {item["name"] for item in entities}
    relations = []
    for item in value.get("relations") or []:
        if not isinstance(item, dict):
            continue
        subject = str(item.get("subject", "")).strip()
        relation = str(item.get("relation", "")).strip()
        object_ = str(item.get("object", "")).strip()
        if subject in names and object_ in names and relation:
            relations.append({"subject": subject, "relation": relation, "object": object_})
    return {"entities": entities, "relations": relations}

