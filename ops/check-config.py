"""Validate the small project-owned Codex configuration (Python 3.11+)."""
from pathlib import Path
import tomllib

root = Path(__file__).resolve().parents[1]
config = tomllib.loads((root / '.codex/config.toml').read_text())
assert config == {'agents': {'enabled': True, 'max_concurrent_threads_per_session': 4}}
names = {'lead', 'planner', 'designer', 'developer', 'qa'}
files = list((root / '.codex/agents').glob('*.toml'))
assert {file.stem for file in files} == names
for file in files:
    role = tomllib.loads(file.read_text())
    assert role['name'] == file.stem
    assert all(isinstance(role.get(key), str) and role[key].strip()
               for key in ('name', 'description', 'developer_instructions'))
    assert set(role) <= {'name', 'description', 'developer_instructions',
                         'model', 'model_reasoning_effort'}
assert '0_총괄' in (root / 'AGENTS.md').read_text()
print('PASS: project config, five agent definitions, main Lead instructions')
