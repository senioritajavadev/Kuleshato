import os
import re
import subprocess

import requests

# Закрепляем node в PATH, чтобы subprocess его нашёл
os.environ["PATH"] = r"C:\Program Files\nodejs;" + os.environ.get("PATH", "")
os.environ["PYTHONIOENCODING"] = "utf-8"

SKILL_PATH = r"C:\Users\User\skills\repo_brief\SKILL.md"
BRIEF_SCRIPT = r"C:\Users\User\skills\repo_brief\scripts\repo_brief.mjs"
LESSONS_PATH = r"C:\Users\User\skills\repo_brief\lessons.md"
GOOD_PATH = r"C:\Users\User\skills\repo_brief\good.md"
MODEL = "qwen3:8b"
API = "http://localhost:11434/api/chat"

LESSON_RE = re.compile(r"\[\[LESSON\s+(.*?)\]\]", re.DOTALL)
GOOD_RE = re.compile(r"\[\[GOOD\s+(.*?)\]\]", re.DOTALL)


def read_file(path):
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    return ""


def append_line(path, line):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(f"- {line.strip()}\n")


def build_system():
    with open(SKILL_PATH, encoding="utf-8") as f:
        skill = f.read().split("---", 2)[2].strip()

    lessons = read_file(LESSONS_PATH)
    goods = read_file(GOOD_PATH)

    parts = [skill]

    if lessons:
        parts.append("\n\nПАМЯТЬ ПРОШЛЫХ ОШИБОК (не повторяй их):\n" + lessons)
    if goods:
        parts.append("\n\nУДАЧНЫЕ ПРИЁМЫ (используй, когда уместно):\n" + goods)

    parts.append(
        "\n\nПРАВИЛА ПАМЯТИ:\n"
        "Маркер [[LESSON ...]] добавляй ТОЛЬКО если ты в этом ответе допустил "
        "фактическую ошибку и понял, в чём именно. Формулируй конкретно: "
        "«не утверждай X, если в брифе Y».\n"
        "Маркер [[GOOD ...]] добавляй ТОЛЬКО если нашёл конкретный приём, "
        "привязанный к данным брифа.\n"
        "НЕ добавляй маркеры за общие советы и стандартные рекомендации. "
        "Если сомневаешься — не добавляй."
    )

    return "\n".join(parts)


history = [{"role": "system", "content": build_system()}]


def run_brief(path):
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.run(
        ["node", BRIEF_SCRIPT, path],
        capture_output=True,
        env=env,
    )

    out = proc.stdout.decode("utf-8", errors="replace")
    err = proc.stderr.decode("utf-8", errors="replace")

    # ОТЛАДКА
    print(f"\n[DEBUG] node вернул код: {proc.returncode}")
    print(f"[DEBUG] Длина вывода: {len(out)} символов")
    if err:
        print(f"[DEBUG] Ошибки node: {err[:200]}")

    return (out + "\n" + err).strip()


def trim_history(limit=21):
    global history
    if len(history) > limit:
        history = [history[0]] + history[-(limit - 1):]


def extract_and_save(reply):
    """Ловит маркеры LESSON/GOOD, сохраняет их и возвращает очищенный текст."""
    saved = []

    # Игнорируем общие фразы и советы, которые не являются уроками
    SKIP_WORDS = ("убедитесь", "проверьте", "добавьте", "используйте",
                  "не забывайте", "ограничение", "анализ", "определение", "запуск")

    def is_meaningful(text):
        t = text.lower().strip()
        if len(t) < 20:
            return False
        if any(t.startswith(w) for w in SKIP_WORDS):
            return False
        return True

    for m in LESSON_RE.findall(reply):
        text = " ".join(m.split())
        if text and is_meaningful(text):
            append_line(LESSONS_PATH, text)
            saved.append(("урок", text))

    for m in GOOD_RE.findall(reply):
        text = " ".join(m.split())
        if text and is_meaningful(text):
            append_line(GOOD_PATH, text)
            saved.append(("приём", text))

    clean = LESSON_RE.sub("", reply)
    clean = GOOD_RE.sub("", clean).strip()

    for kind, text in saved:
        print(f"  [{kind} сохранён: {text}]")

    return clean


def chat():
    r = requests.post(API, json={
        "model": MODEL,
        "messages": history,
        "stream": False,
        "think": False,          # <--- ДОБАВЛЕНО: отключаем режим размышлений
    })

    # ОТЛАДКА
    print(f"\n[DEBUG] Статус Ollama: {r.status_code}")
    if r.status_code != 200:
        print(f"[DEBUG] Текст ошибки Ollama: {r.text}")
        return "Ошибка соединения с Ollama"

    data = r.json()
    message = data.get("message", {})
    reply = message.get("content", "")

    if not reply:
        thinking = message.get("thinking", "")
        print(f"[DEBUG] content пуст, thinking: {repr(thinking[:500])}")
        if thinking:
            reply = thinking

    print(f"\n[DEBUG] Сырой ответ модели: {repr(reply)}")
    clean = extract_and_save(reply)
    history.append({"role": "assistant", "content": clean})
    return clean


print("Агент repo-brief готов. Напиши путь к проекту или вопрос по нему. 'exit' — выход.")
while True:
    user = input("\nВы: ")
    if user.lower() in ("exit", "quit", "выход"):
        break

    m = re.search(r'([A-Za-z]:\\[^\s"\']+|\\\\[^\s"\']+|\.[\\/][^\s"\']*)', user)
    if m:
        path = m.group(1).rstrip('.,;').strip('"').strip("'")
        history.append({"role": "user", "content":
            f"Запусти repo_brief по пути '{path}' и ответь по его выводу. "
            f"Вопрос пользователя: {user}"})
        brief = run_brief(path)
        history.append({"role": "assistant", "content": f"<результат repo_brief>\n{brief}"})
        print("\nАгент:", chat())
    else:
        history.append({"role": "user", "content": user})
        print("\nАгент:", chat())

    trim_history()