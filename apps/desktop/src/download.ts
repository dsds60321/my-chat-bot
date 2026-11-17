import { invoke } from '@tauri-apps/api/core';
import { apiBaseUrl } from './api';
import { native } from './native';
import { authorization, AuthenticationError, type LoginSession } from './session';
import { displaySourceImage, sourceFile, type Attachment, type SourceFile } from './sources';

const maxFileBytes = 20 * 1024 * 1024;

async function fetchSource(source: SourceFile, session: LoginSession, endpoint?: string, signal?: AbortSignal) {
  const file = sourceFile(source);
  if (!file) throw new Error('다운로드할 출처를 찾지 못했어요.');
  const account = session.auth();
  const response = await fetch(`${apiBaseUrl}${endpoint ?? `/api/sources/${file.documentId}`}?version=${file.version}`, {
    signal,
    credentials: 'omit', cache: 'no-store', redirect: 'error',
    headers: { Authorization: authorization(account.username, account.password) },
  });
  if (response.status === 401) throw new AuthenticationError('다시 로그인한 뒤 파일을 다운로드해 주세요.');
  if (response.status === 404 || response.status === 403) throw new Error('출처 파일이 바뀌었거나 접근할 수 없어요. 다시 질문해 주세요.');
  if (response.status === 429) throw new Error('다른 요청을 처리 중이거나 사용 한도에 도달했어요. 잠시 후 다시 다운로드해 주세요.');
  if (!response.ok || !response.headers.get('Content-Type')?.startsWith('application/octet-stream')) {
    throw new Error('출처 파일을 다운로드하지 못했어요.');
  }
  if (Number(response.headers.get('Content-Length')) > maxFileBytes) throw new Error('출처 파일 크기 제한을 초과했어요.');
  const bytes = await response.arrayBuffer();
  if (bytes.byteLength > maxFileBytes) throw new Error('출처 파일 크기 제한을 초과했어요.');
  if (session.auth() !== account) throw new AuthenticationError('현재 계정에서 다시 질문한 뒤 파일을 다운로드해 주세요.');
  return { response, bytes };
}

let imageQueue: Promise<unknown> = Promise.resolve();

export function readSourceImage(file: Attachment, session: LoginSession,
    endpoint?: string, signal?: AbortSignal): Promise<string> {
  const task = imageQueue.then(async () => {
    signal?.throwIfAborted();
    const account = session.auth();
    const { bytes } = await fetchSource(file, session, endpoint, signal);
    const blob = new Blob([bytes], { type: /\.png$/i.test(file.filename) ? 'image/png' : 'image/jpeg' });
    const data = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = () => reject(new Error('이미지를 읽지 못했어요.'));
      reader.readAsDataURL(blob);
    });
    signal?.throwIfAborted();
    if (session.auth() !== account) throw new AuthenticationError('다시 로그인해 주세요.');
    return data;
  });
  imageQueue = task.catch(() => {});
  return task;
}

export async function previewSource(file: Attachment, session: LoginSession, image: HTMLImageElement,
    endpoint?: string, signal?: AbortSignal): Promise<void> {
  await displaySourceImage(image, await readSourceImage(file, session, endpoint, signal));
}

export async function downloadSource(source: SourceFile, session: LoginSession, endpoint?: string): Promise<string> {
  const { response, bytes } = await fetchSource(source, session, endpoint);
  const encodedName = response.headers.get('Content-Disposition')?.match(/(?:^|;)\s*filename\*=UTF-8''([^;]+)/i)?.[1];
  if (!encodedName) throw new Error('서버에서 파일 이름을 받지 못했어요.');
  const filename = decodeURIComponent(encodedName);
  if (native) {
    const nameBytes = new TextEncoder().encode(`${filename}\0`);
    const payload = new Uint8Array(nameBytes.length + bytes.byteLength);
    payload.set(nameBytes);
    payload.set(new Uint8Array(bytes), nameBytes.length);
    return invoke<string>('save_source', payload);
  }
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
  return '내 기기로 파일 사본을 다운로드하고 있어요.';
}
