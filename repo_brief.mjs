#!/usr/bin/env node
// repo_brief.mjs — краткий бриф локального репозитория. Без внешних зависимостей.
// Использование:
//   node repo_brief.mjs <путь-к-репо> [--max-files N] [--include-hidden] [--format json] [--output FILE]

import fs from "node:fs";
import path from "node:path";

const SKIP_DIRS = new Set([
    "node_modules", "venv", ".venv", "env", ".git", ".hg", ".svn",
    "__pycache__", ".mypy_cache", ".pytest_cache", ".ruff_cache",
    "dist", "build", "target", "out", ".next", ".nuxt", ".idea", ".vscode",
    "coverage", ".tox", ".gradle", ".cache",
]);

const MANIFESTS = [
    "package.json", "pyproject.toml", "requirements.txt", "Pipfile",
    "setup.py", "setup.cfg", "Cargo.toml", "go.mod", "pom.xml",
    "build.gradle", "build.gradle.kts", "composer.json", "Gemfile",
    "CMakeLists.txt", "Makefile", "makefile", "Dockerfile",
    "docker-compose.yml", "docker-compose.yaml",
];

const CI_PATHS = [
    ".github/workflows", ".gitlab-ci.yml", ".circleci/config.yml",
    "Jenkinsfile", "azure-pipelines.yml", ".travis.yml", "bitbucket-pipelines.yml",
];

const DOC_FILES = [
    "README.md", "README.rst", "README", "CONTRIBUTING.md", "CHANGELOG.md",
    "CHANGELOG", "SECURITY.md", "LICENSE", "LICENSE.md", "LICENSE.txt",
];

const AGENT_FILES = [
    "AGENTS.md", "CLAUDE.md", "GEMINI.md", ".cursorrules",
];

const ENV_TEMPLATES = [".env.example", ".env.sample", ".env.template", ".env.dist"];

const TEST_DIRS = new Set(["test", "tests", "spec", "__tests__"]);
const TEST_FILE_RE = /(^test_.*\.py$|.*_test\.py$|.*\.(test|spec)\.[jt]sx?$|.*Test\.(java|kt|cs)$|.*_test\.go$)/;

function parseArgs(argv) {
    const args = { repo: ".", maxFiles: 2000, includeHidden: false, format: "md", output: null };
    const rest = [];
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === "--max-files") args.maxFiles = parseInt(argv[++i], 10) || 2000;
        else if (a === "--include-hidden") args.includeHidden = true;
        else if (a === "--format") args.format = argv[++i] || "md";
        else if (a === "--output") args.output = argv[++i] || null;
        else rest.push(a);
    }
    if (rest.length) args.repo = rest[0];
    return args;
}

function walk(root, opts) {
    const files = [];
    const dirs = [];
    let truncated = false;

    function rec(dir) {
        if (files.length >= opts.maxFiles) { truncated = true; return; }
        let entries;
        try { entries = fs.readdirSync(dir, { withFileTypes: true }); }
        catch { return; }
        for (const e of entries) {
            if (files.length >= opts.maxFiles) { truncated = true; return; }
            // Пропускаем скрытые папки, кроме тех, что нужны для CI
            if (!opts.includeHidden && e.name.startsWith(".")) {
                if (![".github", ".gitlab-ci.yml", ".circleci", ".cursorrules"].includes(e.name)) continue;
            }
            const full = path.join(dir, e.name);
            if (e.isDirectory()) {
                if (SKIP_DIRS.has(e.name)) continue;
                dirs.push(path.relative(root, full));
                rec(full);
            } else if (e.isFile()) {
                files.push(path.relative(root, full));
            }
        }
    }
    rec(root);
    return { files, dirs, truncated };
}

function readText(p) {
    try { return fs.readFileSync(p, "utf8"); } catch { return ""; }
}

function detectStack(root, files) {
    const stack = new Set();
    const extCount = {};
    for (const f of files) {
        const ext = path.extname(f).toLowerCase();
        if (ext) extCount[ext] = (extCount[ext] || 0) + 1;
    }
    const has = (name) => files.some((f) => f === name || f.endsWith("/" + name));
    const hasExt = (ext, min = 1) => (extCount[ext] || 0) >= min;

    if (has("package.json")) stack.add("Node.js / JavaScript");
    if (has("tsconfig.json") || hasExt(".ts", 2)) stack.add("TypeScript");
    if (has("pyproject.toml") || has("requirements.txt") || has("setup.py") || has("Pipfile") || hasExt(".py")) stack.add("Python");
    if (has("Cargo.toml") || hasExt(".rs")) stack.add("Rust");
    if (has("go.mod") || hasExt(".go")) stack.add("Go");
    if (has("pom.xml") || has("build.gradle") || has("build.gradle.kts")) stack.add("Java/Kotlin (JVM)");
    if (has("composer.json") || hasExt(".php")) stack.add("PHP");
    if (has("Gemfile") || hasExt(".rb")) stack.add("Ruby");
    if (has("CMakeLists.txt") || hasExt(".cpp") || hasExt(".cc") || hasExt(".c")) stack.add("C/C++");
    if (has("Dockerfile") || has("docker-compose.yml") || has("docker-compose.yaml")) stack.add("Docker");
    if (hasExt(".cs")) stack.add("C#/.NET");

    return { stack: [...stack], extCount };
}

function detectCommands(root, files) {
    const cmds = [];
    const has = (name) => files.some((f) => f === name || f.endsWith("/" + name));

    if (has("package.json")) {
        const pj = JSON.parse(readText(path.join(root, "package.json")) || "{}");
        const scripts = pj.scripts || {};
        for (const [k, v] of Object.entries(scripts)) cmds.push({ source: "package.json", name: k, cmd: v });
        if (pj.packageManager) cmds.push({ source: "package.json", name: "packageManager", cmd: pj.packageManager });
    }
    if (has("Makefile") || has("makefile")) {
        const mk = readText(path.join(root, "Makefile")) || readText(path.join(root, "makefile"));
        const targets = [...mk.matchAll(/^([A-Za-z0-9_.-]+)\s*:/gm)].map((m) => m[1]).filter((t) => !t.startsWith("."));
        for (const t of [...new Set(targets)].slice(0, 30)) cmds.push({ source: "Makefile", name: t, cmd: `make ${t}` });
    }
    if (has("pyproject.toml") || has("requirements.txt")) {
        cmds.push({ source: "python", name: "install deps", cmd: "python -m pip install -r requirements.txt" });
    }
    if (has("pyproject.toml") || has("requirements.txt") || hasExtPy(files)) {
        cmds.push({ source: "python", name: "tests", cmd: "python -m pytest" });
    }
    if (has("Cargo.toml")) {
        cmds.push({ source: "cargo", name: "build", cmd: "cargo build" });
        cmds.push({ source: "cargo", name: "tests", cmd: "cargo test" });
    }
    if (has("go.mod")) {
        cmds.push({ source: "go", name: "build", cmd: "go build ./..." });
        cmds.push({ source: "go", name: "tests", cmd: "go test ./..." });
    }
    return cmds;
}

function hasExtPy(files) { return files.some((f) => f.endsWith(".py")); }

function detectCI(root) {
    const found = [];
    for (const p of CI_PATHS) {
        const full = path.join(root, p);
        if (fs.existsSync(full)) {
            if (fs.statSync(full).isDirectory()) {
                const inner = fs.readdirSync(full).filter((n) => /\.(ya?ml)$/.test(n));
                if (inner.length) found.push(`${p}/ (${inner.join(", ")})`);
            } else {
                found.push(p);
            }
        }
    }
    return found;
}

function detectTests(files) {
    const out = [];
    for (const f of files) {
        const parts = f.split(/[\\/]/);
        if (parts.some((p) => TEST_DIRS.has(p)) || TEST_FILE_RE.test(parts[parts.length - 1])) {
            out.push(f);
            if (out.length >= 40) break;
        }
    }
    return out;
}

function detectDocs(files) {
    return DOC_FILES.filter((d) => files.some((f) => f === d || f.endsWith("/" + d)));
}

function detectAgents(files) {
    return AGENT_FILES.filter((d) => files.some((f) => f === d || f.endsWith("/" + d)));
}

function detectEnv(root, files) {
    const templates = ENV_TEMPLATES.filter((d) => files.some((f) => f === d || f.endsWith("/" + d)));
    const realEnv = files.filter((f) => /(^|\/)\.env(\.|$)/.test(f) && !ENV_TEMPLATES.some((t) => f.endsWith(t)));
    return { templates, realEnv };
}

function buildBrief(root, opts) {
    const { files, dirs, truncated } = walk(root, opts);
    const { stack, extCount } = detectStack(root, files);
    const commands = detectCommands(root, files);
    const ci = detectCI(root);
    const tests = detectTests(files);
    const docs = detectDocs(files);
    const agents = detectAgents(files);
    const env = detectEnv(root, files);

    const risks = [];
    if (!docs.some((d) => /^README/i.test(d))) risks.push("Нет README.");
    if (!tests.length) risks.push("Не найдено тестов.");
    if (!ci.length) risks.push("Не найден CI.");
    if (env.realEnv.length && !env.templates.length) risks.push("Есть .env-файлы, но нет .env.example/.sample.");
    if (truncated) risks.push(`Достигнут лимит файлов (${opts.maxFiles}); бриф неполный.`);
    if (!agents.length) risks.push("Нет файлов инструкций для агентов (AGENTS.md/CLAUDE.md и т.п.).");

    return {
        root: path.resolve(root),
        generatedAt: new Date().toISOString(),
        filesScanned: files.length,
        dirsScanned: dirs.length,
        truncated,
        stack,
        extCount,
        commands,
        ci,
        tests,
        docs,
        agents,
        env,
        risks,
    };
}

function toMarkdown(b) {
    const L = [];

    // Предупреждение, если папка пуста
    if (b.filesScanned === 0) {
        L.push("> **Внимание:** в папке не найдено ни одного файла. Возможно, путь неверный или папка пуста.\n");
    }

    L.push(`# Repo Brief: ${path.basename(b.root)}`);
    L.push("");
    L.push(`- Путь: \`${b.root}\``);
    L.push(`- Сгенерировано: ${b.generatedAt}`);
    L.push(`- Файлов просканировано: ${b.filesScanned} (каталогов: ${b.dirsScanned})${b.truncated ? " — **лимит достигнут**" : ""}`);
    L.push("");
    L.push("## Стек");
    L.push(b.stack.length ? b.stack.map((s) => `- ${s}`).join("\n") : "- Не определён по манифестам/расширениям.");
    L.push("");
    L.push("## Команды");
    L.push(b.commands.length ? b.commands.map((c) => `- \`${c.cmd}\` (${c.source}: ${c.name})`).join("\n") : "- Не найдено.");
    L.push("");
    L.push("## CI");
    L.push(b.ci.length ? b.ci.map((c) => `- ${c}`).join("\n") : "- Не найден.");
    L.push("");
    L.push("## Тесты");
    L.push(b.tests.length ? b.tests.map((t) => `- ${t}`).join("\n") : "- Не найдено.");
    L.push("");
    L.push("## Документация");
    L.push(b.docs.length ? b.docs.map((d) => `- ${d}`).join("\n") : "- Не найдена.");
    L.push("");
    L.push("## Инструкции для агентов");
    L.push(b.agents.length ? b.agents.map((a) => `- ${a}`).join("\n") : "- Не найдены.");
    L.push("");
    L.push("## Env");
    if (b.env.templates.length) L.push(b.env.templates.map((e) => `- шаблон: ${e}`).join("\n"));
    if (b.env.realEnv.length) L.push(b.env.realEnv.map((e) => `- реальный env: ${e}`).join("\n"));
    if (!b.env.templates.length && !b.env.realEnv.length) L.push("- Не найдено.");
    L.push("");
    L.push("## Риски и пробелы");
    L.push(b.risks.length ? b.risks.map((r) => `- ${r}`).join("\n") : "- Не выявлено.");

    return L.join("\n");
}

function main() {
    const opts = parseArgs(process.argv.slice(2));
    if (!fs.existsSync(opts.repo)) {
        console.error(`Путь не существует: ${opts.repo}`);
        process.exit(1);
    }
    const brief = buildBrief(opts.repo, opts);
    const out = opts.format === "json" ? JSON.stringify(brief, null, 2) : toMarkdown(brief);

    if (opts.output) {
        fs.writeFileSync(opts.output, out, "utf8");
        console.error(`Записано: ${opts.output}`);
    } else {
        process.stdout.write(out + "\n");
    }
}

main();