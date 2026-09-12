/**
 * Mock Agent：模拟后端 AgentLoop 的 SSE 事件流。
 *
 * 事件序列严格对齐真实实现（Agent/AgentLoop.py、ModelNode.py、ToolNodes.py、
 * TerminalNodes.py），包括事件类型、payload 字段与发生顺序，方便未来无缝切换。
 */

import type {
  AgentEventEnvelope,
  AgentEventType,
  DocumentItem,
} from '../types'
import type { MockDb, MockMemory } from './db'
import { id } from './db'

export interface MockAgentParams {
  db: MockDb
  userId: string
  sessionId: string
  requestId: string
  messageId: string
  message: string
  signal: AbortSignal
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

function randomBetween(min: number, max: number): number {
  return min + Math.random() * (max - min)
}

/** 把文本按随机长度切块，模拟流式输出 */
function chunkText(text: string): string[] {
  const chunks: string[] = []
  let i = 0
  while (i < text.length) {
    const size = Math.floor(randomBetween(2, 7))
    chunks.push(text.slice(i, i + size))
    i += size
  }
  return chunks
}

export async function* mockAgentEvents(
  params: MockAgentParams,
): AsyncGenerator<AgentEventEnvelope> {
  const { db, userId, message, signal } = params
  const request_id = params.requestId
  const message_id = params.messageId
  let seq = 0
  let modelSteps = 0
  let toolRounds = 0

  const emit = function* <P extends Record<string, unknown>>(
    type: AgentEventType,
    payload: P,
  ): Generator<AgentEventEnvelope> {
    seq += 1
    yield {
      type,
      request_id,
      session_id: params.sessionId,
      event_seq: seq,
      payload,
    }
  }

  const checkAborted = () => {
    if (signal.aborted) throw new DOMException('已中止', 'AbortError')
  }

  const streamText = async function* (modelStep: number, text: string) {
    for (const delta of chunkText(text)) {
      checkAborted()
      await sleep(randomBetween(25, 65))
      yield* emit('text_delta', { model_step: modelStep, delta })
    }
  }

  yield* emit('run_started', { message_id })

  // ---------- 错误演示 ----------
  if (message.includes('#error') || message.includes('触发错误')) {
    await sleep(400)
    checkAborted()
    yield* emit('error', {
      code: 'model_call_failed',
      message: '模型服务暂时不可用，请稍后重试（Mock 演示错误）',
      phase: 'model',
      retryable: true,
    })
    yield* emit('run_finished', {
      status: 'failed',
      model_steps: 1,
      tool_rounds: 0,
      usage: {},
    })
    return
  }

  const readyDocs = db.listReadyDocuments(userId)
  const wantsRag =
    readyDocs.length > 0 &&
    /[？?]|什么|为什么|怎么|如何|讲解|解释|总结|分析|介绍|资料|文档|知识库|pdf|PDF/.test(message)
  const wantsMemoryStore = /记住|记一下|以后记得|帮我保存/.test(message)
  const wantsMemoryQuery = /我之前|上次|还记得|我的记忆|我说过/.test(message)

  // ---------- 第一步模型调用（决定是否使用工具） ----------
  modelSteps = 1
  const inputTokens = 600 + message.length * 2
  yield* emit('model_step_started', {
    model_step: 1,
    input_tokens: inputTokens,
    tools_enabled: true,
  })

  if (wantsRag || wantsMemoryStore || wantsMemoryQuery) {
    // 工具前置说明文本
    const preamble = wantsRag
      ? '我先检索你的知识库，看看有哪些相关内容。'
      : wantsMemoryStore
        ? '好的，我来把这条信息保存到记忆。'
        : '我查一下之前保存的记忆。'
    for await (const event of streamText(1, preamble)) {
      yield event
    }

    yield* emit('model_step_finished', {
      model_step: 1,
      outcome: 'tool_request',
      consumed_result_ids: [],
      usage: {
        input_tokens: inputTokens,
        output_tokens: preamble.length,
        total_tokens: inputTokens + preamble.length,
      },
    })

    // ---------- 工具执行 ----------
    toolRounds = 1
    const toolCalls = buildToolCalls(message, readyDocs)
    for (const call of toolCalls) {
      checkAborted()
      yield* emit('tool_started', {
        model_step: 1,
        tool_call_id: call.toolCallId,
        name: call.name,
        arguments: call.arguments,
      })
      await sleep(randomBetween(900, 1600))
      checkAborted()
      if (call.name === 'memory__memory_store' && call.memory) {
        db.addMemory(userId, call.memory)
      }
      yield* emit('tool_finished', {
        tool_call_id: call.toolCallId,
        name: call.name,
        outcome: 'completed',
        business_status: 'ok',
        content: call.content,
        result: call.result,
      })
    }

    // ---------- 第二步模型调用（基于工具结果回答） ----------
    modelSteps = 2
    const step2Input = inputTokens + toolCalls.reduce((n, c) => n + c.content.length, 0)
    yield* emit('model_step_started', {
      model_step: 2,
      input_tokens: step2Input,
      tools_enabled: true,
    })
    const answer = buildToolAnswer(message, toolCalls)
    for await (const event of streamText(2, answer)) {
      yield event
    }
    const outputTokens = preamble.length + answer.length
    yield* emit('model_step_finished', {
      model_step: 2,
      outcome: 'final_answer',
      consumed_result_ids: [],
      usage: {
        input_tokens: step2Input,
        output_tokens: answer.length,
        total_tokens: step2Input + answer.length,
      },
    })
    yield* emit('message_completed', {
      model_step: 2,
      content: answer,
      usage: {
        input_tokens: inputTokens + step2Input,
        output_tokens: outputTokens,
        total_tokens: inputTokens + step2Input + outputTokens,
      },
    })
    yield* emit('run_finished', {
      status: 'completed',
      model_steps: modelSteps,
      tool_rounds: toolRounds,
      usage: {
        input_tokens: inputTokens + step2Input,
        output_tokens: outputTokens,
        total_tokens: inputTokens + step2Input + outputTokens,
      },
    })
    return
  }

  // ---------- 纯文本回答（无工具） ----------
  const answer = buildPlainAnswer(message, readyDocs.length > 0)
  for await (const event of streamText(1, answer)) {
    yield event
  }
  yield* emit('model_step_finished', {
    model_step: 1,
    outcome: 'final_answer',
    consumed_result_ids: [],
    usage: {
      input_tokens: inputTokens,
      output_tokens: answer.length,
      total_tokens: inputTokens + answer.length,
    },
  })
  yield* emit('message_completed', {
    model_step: 1,
    content: answer,
    usage: {
      input_tokens: inputTokens,
      output_tokens: answer.length,
      total_tokens: inputTokens + answer.length,
    },
  })
  yield* emit('run_finished', {
    status: 'completed',
    model_steps: 1,
    tool_rounds: 0,
    usage: {
      input_tokens: inputTokens,
      output_tokens: answer.length,
      total_tokens: inputTokens + answer.length,
    },
  })
}

// ============ 工具调用构造 ============

interface PlannedToolCall {
  toolCallId: string
  name: string
  arguments: Record<string, unknown>
  content: string
  result: unknown
  memory?: MockMemory
}

function buildToolCalls(message: string, readyDocs: DocumentItem[]): PlannedToolCall[] {
  const calls: PlannedToolCall[] = []

  if (readyDocs.length > 0 && !/记住|记一下/.test(message)) {
    const topDoc = readyDocs[0]
    const chunks = buildMockChunks(message, topDoc)
    const result = {
      status: 'ok',
      query: message.slice(0, 40),
      results: chunks,
    }
    calls.push({
      toolCallId: id('call'),
      name: 'rag__rag_search',
      arguments: {
        query: message.slice(0, 40),
        expanded_queries: null,
        hypothetical_doc: null,
      },
      content: JSON.stringify(result),
      result,
    })
  }

  if (/记住|记一下|以后记得|帮我保存/.test(message)) {
    const content = message.replace(/^(请|帮我)?(记住|记一下|以后记得)[:：，,\s]*/, '').trim() || message
    const memory: MockMemory = {
      id: id('mem'),
      content,
      memory_type: 'semantic',
      importance: 0.8,
      createdAt: new Date().toISOString(),
    }
    const result = {
      status: 'ok',
      memory_id: memory.id,
      memory_type: 'semantic',
      content,
    }
    calls.push({
      toolCallId: id('call'),
      name: 'memory__memory_store',
      arguments: { content, memory_type: 'semantic', importance: 0.8 },
      content: JSON.stringify(result),
      result,
      memory,
    })
  }

  return calls
}

function buildMockChunks(
  message: string,
  doc: DocumentItem,
): Array<Record<string, unknown>> {
  const keyword = extractKeyword(message)
  const pages = [3, 12, 27, 41]
  return pages.slice(0, 3).map((page, i) => ({
    chunk_id: id('chunk'),
    document_id: doc.id,
    source: doc.name,
    page,
    score: Number((0.92 - i * 0.13).toFixed(2)),
    text: `《${doc.name}》第 ${page} 页：${keyword}的核心定义是……在实际教学中，可以先用一个具体例子引入${keyword}，再逐步展开原理推导。第 ${page} 页的图表给出了${keyword}的完整流程，建议结合练习题加深理解。`,
  }))
}

function extractKeyword(message: string): string {
  const stripped = message.replace(/[？?！!。，,.、\s]/g, ' ')
  const stopWords = ['什么', '怎么', '如何', '为什么', '请', '我', '你', '的', '是', '讲', '解释', '总结', '一下']
  const words = stripped
    .split(/\s+/)
    .flatMap((part) => part.split(/(什么|怎么|如何|为什么|讲解|解释|总结|介绍|分析)/))
    .map((w) => w.trim())
    .filter((w) => w.length > 0 && !stopWords.includes(w))
  return words.slice(0, 4).join('') || '这个主题'
}

// ============ 回答生成 ============

function buildPlainAnswer(message: string, hasDocs: boolean): string {
  if (/^(你好|您好|hi|hello|嗨)/i.test(message)) {
    return [
      '你好！我是你的**智能体老师**。',
      '',
      '我可以：',
      '',
      '- 讲解你上传的学习资料（PDF 会自动转换并建立知识库）',
      '- 围绕知识点提问、诊断你的作答并纠错',
      '- 记住你的学习偏好和重要信息，跨会话使用',
      '',
      hasDocs
        ? '你的知识库已有文档，可以直接问我资料里的内容。'
        : '你可以先到「文档」页上传学习资料，之后就能针对文档内容提问。',
    ].join('\n')
  }

  if (/你是谁|自我介绍|你能做什么|功能/.test(message)) {
    return [
      '我是一个面向**个人学习**的智能体老师，核心能力包括：',
      '',
      '1. **知识库问答**：基于你上传的文档检索回答，并标注来源与页码',
      '2. **苏格拉底式教学**：讲解后适时向你提问，诊断理解偏差',
      '3. **长期记忆**：记住你的学习目标、薄弱点和偏好，下次会话直接续上',
      '4. **多轮上下文管理**：长对话自动摘要，重要工具结果完整保留',
      '',
      '> 试试问我一个你资料里的问题，或说「记住我正在准备考试」。',
    ].join('\n')
  }

  const keyword = extractKeyword(message)
  return [
    `关于**${keyword}**，我先给你一个整体框架：`,
    '',
    `1. **是什么**：${keyword}的基本概念与适用场景`,
    `2. **为什么**：它解决了什么问题，动机从何而来`,
    `3. **怎么用**：典型步骤与常见陷阱`,
    '',
    '```text',
    `入门 ${keyword} 的三步路径：`,
    '  ① 建立直觉 —— 用最小例子跑通一遍',
    '  ② 对照原理 —— 把例子里的每一步映射到定义',
    '  ③ 变式练习 —— 换条件重做，检验是否真正理解',
    '```',
    '',
    hasDocs
      ? '你的知识库里有相关文档，我可以结合具体页码详细展开——想先从哪一部分开始？'
      : '如果上传相关文档，我可以基于原文给出更精确、带出处的讲解。',
  ].join('\n')
}

function buildToolAnswer(message: string, calls: PlannedToolCall[]): string {
  const ragCall = calls.find((c) => c.name === 'rag__rag_search')
  const memoryCall = calls.find((c) => c.name === 'memory__memory_store')

  if (memoryCall) {
    const memContent = String(
      (memoryCall.arguments as { content?: string }).content ?? '',
    )
    return [
      '已经记住了！',
      '',
      '我把这条信息保存为**语义记忆**（importance 0.8）：',
      '',
      `> ${memContent}`,
      '',
      '之后在任何会话里提到相关内容，我都会想起它。',
    ].join('\n')
  }

  if (ragCall) {
    const result = ragCall.result as {
      results: Array<{ source: string; page: number; score: number; text: string }>
    }
    const keyword = extractKeyword(message)
    const rows = result.results
      .map((r, i) => `| ${i + 1} | ${r.source} | 第 ${r.page} 页 | ${r.score} |`)
      .join('\n')
    return [
      `根据你知识库的检索结果，**${keyword}**可以这样理解：`,
      '',
      `### 核心内容`,
      '',
      `${result.results[0].text}`,
      '',
      `### 要点拆解`,
      '',
      `- **定义与直觉**：先用最小可运行例子建立直觉，再补齐严格定义`,
      `- **常见误区**：初学者容易忽略适用前提，建议对照第 ${result.results[1].page} 页的图表自查`,
      `- **练习建议**：完成书中对应章节的变式练习，检验迁移能力`,
      '',
      `### 检索来源`,
      '',
      '| # | 文档 | 位置 | 相关度 |',
      '| --- | --- | --- | --- |',
      rows,
      '',
      '以上内容基于你的私有文档，如需针对某一条展开或纠错，直接告诉我。',
    ].join('\n')
  }

  return '（没有可用工具结果）'
}
