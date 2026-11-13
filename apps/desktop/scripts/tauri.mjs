import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { cleanBuildOutputs } from './clean-build.mjs';

const args = process.argv.slice(2);
if (['build', 'bundle', 'dev'].includes(args[0])) {
  const branding = JSON.parse(readFileSync(new URL('../../../branding.json', import.meta.url), 'utf8'));
  const name = branding.appTitle ?? branding.name;
  if (typeof name !== 'string' || !name.trim() || name.trim().length > 40
      || /[\x00-\x1f<>:"/\\|?*]/u.test(name) || /[. ]$/u.test(name)) {
    throw new Error('branding.json appTitle은 1~40자이며 파일명에 사용할 수 있어야 합니다.');
  }
  args.push('--config', JSON.stringify({ productName: name.trim(), bundle: { publisher: name.trim() } }));
}
const require = createRequire(import.meta.url);
const cli = require.resolve('@tauri-apps/cli/tauri.js');
const output = cleanBuildOutputs(args, fileURLToPath(new URL('../src-tauri/target/', import.meta.url)));
if (output) console.log(`이전 배포 산출물 정리 완료: ${output}`);
const result = spawnSync(process.execPath, [cli, ...args], { stdio: 'inherit', env: process.env });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
