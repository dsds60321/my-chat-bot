export interface FolderRequest {
  id: string; path: string; author: string; state: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'; createdAt: string; updatedAt: string;
}
export interface FolderCatalog { folders: string[]; requests: FolderRequest[]; canCreateDirectly: boolean; canCreateProject: boolean }
export interface FolderRow { path: string; name: string; depth: number; children: boolean; pending: boolean }

export function validFolderPath(path: string): boolean {
  return path.length > 0 && path.length <= 1024 && path.split('/').length <= 16
    && path.split('/').every(part => /^[\p{L}\p{N}_-][\p{L}\p{M}\p{N} ._-]*$/u.test(part) && !/[ .]$/.test(part));
}

export function folderCatalog(value: unknown): FolderCatalog {
  if (!value || typeof value !== 'object') throw new Error('폴더 목록을 읽지 못했어요.');
  const data = value as FolderCatalog;
  if (!Array.isArray(data.folders) || !data.folders.every(path => typeof path === 'string' && validFolderPath(path))
    || !Array.isArray(data.requests) || !data.requests.every(request => request && typeof request.id === 'string'
      && typeof request.path === 'string' && validFolderPath(request.path) && typeof request.author === 'string'
      && ['PENDING_APPROVAL', 'APPROVED', 'REJECTED'].includes(request.state) && typeof request.createdAt === 'string'
      && typeof request.updatedAt === 'string') || typeof data.canCreateDirectly !== 'boolean' || typeof data.canCreateProject !== 'boolean') {
    throw new Error('폴더 목록을 읽지 못했어요.');
  }
  return { ...data, folders: [...new Set(data.folders)].sort((a, b) => a.localeCompare(b, 'ko')) };
}

export function folderRows(catalog: FolderCatalog, expanded: Set<string>, query = ''): FolderRow[] {
  const pending = new Set(catalog.requests.filter(request => request.state === 'PENDING_APPROVAL').map(request => request.path));
  const actual = new Set(catalog.folders);
  const paths = [...new Set([...actual, ...pending])].sort((a, b) => a.localeCompare(b, 'ko'));
  const filter = query.trim().toLocaleLowerCase();
  return paths.filter(path => {
    const segments = path.split('/');
    const parents = segments.slice(0, -1).map((_, index) => segments.slice(0, index + 1).join('/'));
    if (parents.some(parent => !actual.has(parent))) return false;
    if (filter) return path.toLocaleLowerCase().includes(filter)
      || paths.some(child => child.startsWith(path + '/') && child.toLocaleLowerCase().includes(filter));
    return parents.every(parent => expanded.has(parent));
  }).map(path => ({ path, name: path.split('/').at(-1)!, depth: path.split('/').length - 1,
    children: actual.has(path) && paths.some(child => child.startsWith(path + '/')),
    pending: !actual.has(path) && pending.has(path) }));
}

export function folderCreation(parentPath: string, name: string): { parentPath: string; name: string; path: string } {
  const label = name.trim();
  const path = parentPath ? parentPath + '/' + label : label;
  if (!label || label.length > (parentPath ? 100 : 64) || !validFolderPath(path) || label.includes('/') || (!parentPath && !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(label))) {
    throw new Error(parentPath ? '폴더 이름은 1~100자로 입력해 주세요. /, \\, . 또는 .. 경로는 사용할 수 없어요.'
      : '프로젝트 ID는 영문 소문자 또는 숫자로 시작하고 -와 _를 사용할 수 있어요.');
  }
  return { parentPath, name: label, path };
}
