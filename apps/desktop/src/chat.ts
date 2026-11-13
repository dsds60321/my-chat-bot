import { authorization, AuthenticationError } from './session.ts';
import type { Attachment } from './sources.ts';
import { apiBaseUrl } from './api.ts';

export interface Source { documentId?: string; version?: string; title: string; path: string; section?: string; row?: number | null; attachments?: Attachment[] }
export interface StreamEvent { event: string; data: Record<string, unknown> }

// Parse lines independently of HTTP chunks; CRLF and UTF-8 may split anywhere.
export class SseParser {
  private buffer = '';
  private event = 'message';
  private data: string[] = [];
  push(text: string, final = false): StreamEvent[] {
    this.buffer += text;
    const events: StreamEvent[] = [];
    for (;;) {
      const at = this.buffer.search(/[\r\n]/);
      if (at < 0 || (!final && at === this.buffer.length - 1 && this.buffer[at] === '\r')) break;
      const line = this.buffer.slice(0, at);
      const end = this.buffer[at] === '\r' && this.buffer[at + 1] === '\n' ? 2 : 1;
      this.buffer = this.buffer.slice(at + end);
      if (line === '') {
        if (this.data.length) {
          let value: unknown;
          try { value = JSON.parse(this.data.join('\n')); }
          catch { throw new Error('잘못된 응답 이벤트입니다.'); }
          if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('잘못된 응답 이벤트입니다.');
          events.push({ event: this.event, data: value as Record<string, unknown> });
        }
        this.event = 'message'; this.data = [];
      } else if (!line.startsWith(':')) {
        const colon = line.indexOf(':');
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? '' : line.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'event') this.event = value;
        if (field === 'data') this.data.push(value);
        if (this.data.join('\n').length > 65536) throw new Error('응답 이벤트가 너무 큽니다.');
      }
    }
    if (this.buffer.length > 65536) throw new Error('응답 이벤트가 너무 큽니다.');
    return events;
  }
}

function limitMessage(code: unknown, seconds: unknown): string {
  const message = code === 'RATE_LIMITED' ? 'AI 요청 한도를 초과했습니다.' : '서버 요청 한도를 초과했습니다.';
  if (typeof seconds !== 'number' || !Number.isSafeInteger(seconds) || seconds < 0 || seconds > 2147483647) {
    return `${message} 서버에서 재시도 가능 시각을 받지 못했습니다.`;
  }
  const availableAt = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'medium',
  }).format(Date.now() + seconds * 1000);
  return `${message} 한국 시간 ${availableAt} 이후에 다시 시도해 주세요.`;
}

export async function streamChat(question: string, username: string, password: string,
  signal: AbortSignal, onEvent: (event: StreamEvent) => void): Promise<void> {
  const auth = authorization(username, password);
  const response = await fetch(`${apiBaseUrl}/api/chat/stream`, {
    method: 'POST', signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: auth },
    body: JSON.stringify({ question }),
  });
  if (!response.ok) {
    const messages: Record<number, string> = { 400: '질문 입력을 확인해 주세요.',
      403: '이 자료에 접근할 권한이 없어요.', 503: 'AI 서버를 사용할 수 없어요.' };
    if (response.status === 401) throw new AuthenticationError('로그인이 만료되었어요. 다시 로그인해 주세요.');
    if (response.status === 429) {
      const failure = await response.json().catch(() => null);
      if (failure?.code === 'REQUEST_IN_PROGRESS') throw new Error('이미 답변을 생성 중입니다. 완료된 뒤 다시 시도해 주세요.');
      const header = response.headers.get('Retry-After');
      throw new Error(limitMessage(failure?.code === 'LOCAL_RATE_LIMITED' ? failure.code : null,
        failure?.retryAfterSeconds ?? (header && /^\d+$/.test(header) ? Number(header) : null)));
    }
    throw new Error(messages[response.status] ?? 'API 요청에 실패했어요. 서버 상태를 확인해 주세요.');
  }
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('서버 스트리밍 응답 형식이 올바르지 않아요.');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  let terminal = false;
  let length = 0;
  try {
    while (!terminal) {
      const { value, done } = await reader.read();
      signal.throwIfAborted();
      const text = done ? decoder.decode() : decoder.decode(value, { stream: true });
      length += text.length;
      if (length > 500000) throw new Error('답변 길이 제한을 초과했어요.');
      for (const event of parser.push(text, done)) {
        if (event.event === 'error') {
          if (event.data.code === 'RATE_LIMITED' || event.data.code === 'LOCAL_RATE_LIMITED') {
            throw new Error(limitMessage(event.data.code, event.data.retryAfterSeconds));
          }
          if (event.data.code === 'ANSWER_LENGTH_LIMIT') throw new Error('AI 답변이 길이 한도에 도달해 중간에 끝났어요. 질문을 나누어 다시 물어봐 주세요.');
          if (event.data.code === 'TRUNCATED_RESPONSE' || event.data.code === 'UPSTREAM_TIMEOUT') {
            throw new Error('AI 답변이 중간에 끊겼어요. 다시 시도해 주세요.');
          }
          throw new Error('답변 생성에 실패했어요. 잠시 후 다시 시도해 주세요.');
        }
        onEvent(event);
        if (event.event === 'done') { terminal = true; break; }
      }
      if (done) break;
    }
    if (!terminal) throw new Error('답변 연결이 중간에 끊겼어요. 다시 시도해 주세요.');
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
