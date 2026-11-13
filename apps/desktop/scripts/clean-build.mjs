import { readdirSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';

export function buildOption(args, flag) {
  const inline = args.find(arg => arg.startsWith(flag + '='));
  if (inline) return inline.slice(flag.length + 1);
  const index = args.indexOf(flag);
  return index < 0 ? undefined : args[index + 1];
}

export function buildTarget(args, env = process.env) {
  return buildOption(args, '--target') ?? buildOption(args, '-t') ?? env.CARGO_BUILD_TARGET;
}

export function cleanBuildOutputs(args, targetRoot, env = process.env) {
  if (args[0] !== 'build' || args.includes('--help') || args.includes('-h')) return null;
  const target = buildTarget(args, env);
  const profile = buildOption(args, '--profile') ?? (args.includes('--debug') || args.includes('-d') ? 'debug' : 'release');
  for (const value of [target, profile]) {
    if (value !== undefined && !/^[a-zA-Z0-9_-]+$/.test(value)) throw new Error('빌드 대상 또는 프로필을 확인해 주세요.');
  }
  const root = env.CARGO_TARGET_DIR ? resolve(env.CARGO_TARGET_DIR) : targetRoot;
  const output = target ? join(root, target, profile) : join(root, profile);
  rmSync(join(output, 'bundle'), { recursive: true, force: true });
  let files;
  try { files = readdirSync(output); }
  catch (error) { if (error.code === 'ENOENT') return output; throw error; }
  for (const file of files) {
    if (/^(?:easytouse-ai-pet|easytouse_ai_pet|my-chat-bot|my_chat_bot)(?:\.exe|\.pdb|\.d)?$/.test(file)) {
      rmSync(join(output, file), { force: true });
    }
  }
  return output;
}
