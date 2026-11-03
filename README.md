# Kuleshato

Binding for an open source model (Ollama, qwen3:8b)

A local AI agent that scans a repository and answers questions about it based on actual data rather than guesses.

The project consists of two parts:

| File | Purpose |
|---|---|
| `scripts/repo_brief.mjs` | CLI utility (Node.js, zero dependencies): scans a repository and generates a brief summary |
| `agent_repo_brief.py` | Console agent (Python): chats with a local model via Ollama, using the brief as context |

## What repo_brief.mjs does

It walks the repository directory tree (skipping `node_modules`, `.git`, `venv`, `dist`, etc.) and collects, based on the actual files:

- **Stack** — from manifests and file extensions (`package.json`, `pyproject.toml`, `Cargo.toml`, `go.mod`, `Dockerfile`, etc.)
- **Commands** — scripts from `package.json`, targets from `Makefile`, standard build/test commands
- **CI** — GitHub Actions, GitLab CI, Jenkins, CircleCI, etc.
- **Tests** — test files and directories (`tests/`, `test_*.py`, `*.test.ts`, …)
- **Documentation** — `README`, `CONTRIBUTING`, `CHANGELOG`, licenses
- **Agent instructions** — `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.cursorrules`
- **Env files** — templates (`.env.example`) and real `.env` files
- **Risks and gaps** — missing README, tests, CI, env templates, etc.

### Usage

```bash
node repo_brief.mjs <path-to-repo> [options]

# Options:
#   --max-files N      limit on the number of scanned files (default 2000)
#   --include-hidden   include hidden files and directories
#   --format json      output JSON instead of Markdown
#   --output FILE      write the result to a file
```

## How the agent works

`agent_repo_brief.py` is a REPL loop in the terminal:

1. The user types a project path (e.g. `C:\projects\my-app`) or a question.
2. If a path is found in the message, the agent runs `repo_brief.mjs` and adds the result to the conversation history.
3. The question and the brief are sent to the local model (Ollama, `qwen3:8b`).
4. The model's reply is printed to the chat.

### Agent memory

The agent accumulates experience across sessions via markers in the model's replies:

- `[[LESSON ...]]` — a recorded factual mistake (saved to `lessons.md` and fed back to the model as "memory of past mistakes")
- `[[GOOD ...]]` — a successful technique tied to the brief's data (saved to `good.md`)

Only specific, meaningful items are saved; generic advice is filtered out.

## Requirements

- **Node.js** — for `repo_brief.mjs` (no external npm dependencies)
- **Python 3** with the `requests` package — for the agent
- **Ollama** with the `qwen3:8b` model, running at `http://localhost:11434`

## Installation and running

```bash
# 1. Make sure Ollama is running and the model is downloaded
ollama pull qwen3:8b

# 2. Install Python dependencies
python -m pip install requests

# 3. Run the agent
python agent_repo_brief.py
```

## Skill structure

```
repo_brief/
├── SKILL.md               # model instructions (system prompt)
├── scripts/
│   └── repo_brief.mjs     # repository scanner
├── lessons.md             # accumulated mistakes (memory)
└── good.md                # accumulated techniques (memory)
```

## Configuration

Paths and parameters are set at the top of `agent_repo_brief.py`:

- `SKILL_PATH`, `BRIEF_SCRIPT`, `LESSONS_PATH`, `GOOD_PATH` — paths to the skill files
- `MODEL` — the Ollama model name
- `API` — the Ollama API address
