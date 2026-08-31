#!/usr/bin/env python3
"""Call an OpenAI-compatible chat API to review a PR diff, write markdown to stdout file.

Usage: ai_review.py <diff-file> <output-md>

Env:
  AI_API_KEY   (required) API key
  AI_BASE_URL  (required) OpenAI-compatible base url, e.g. https://api.moonshot.cn/v1
  AI_MODEL     (required) model name
  PR_TITLE     optional PR title for context
  PR_BODY      optional PR description for context
"""

import json
import os
import sys
import urllib.request

MAX_DIFF_CHARS = 100_000  # keep prompt within model context; large PRs get truncated

SYSTEM_PROMPT = """你是一位资深的 Java 后端工程师，精通 Spring Boot、高并发交易系统与事件溯源架构。
请 review 以下 GitHub PR 的 diff。要求：
1. 用中文输出，Markdown 格式。
2. 按严重程度分级列出发现的问题：🔴 必须修复（bug/正确性问题）、🟡 建议改进（设计/可读性）、🟢 可选优化。
3. 每个问题引用具体的文件名和大致位置，给出修改建议。
4. 特别关注：并发与线程安全、BigDecimal 精度处理、资产/金额计算的正确性、边界条件、测试覆盖缺口。
5. 如果没有发现实质问题，明确给出 LGTM 并简要说明理由，不要硬凑问题。
6. 最后给出一句话总体评价。"""


def main() -> int:
    diff_path, out_path = sys.argv[1], sys.argv[2]
    with open(diff_path, encoding="utf-8", errors="replace") as f:
        diff = f.read()

    truncated = len(diff) > MAX_DIFF_CHARS
    if truncated:
        diff = diff[:MAX_DIFF_CHARS]

    api_key = os.environ["AI_API_KEY"]
    base_url = os.environ.get("AI_BASE_URL", "https://api.moonshot.cn/v1").rstrip("/")
    model = os.environ.get("AI_MODEL", "kimi-k2-0711-preview")

    context = f"PR 标题：{os.environ.get('PR_TITLE', '')}\nPR 描述：{os.environ.get('PR_BODY', '') or '(无)'}\n\n"
    note = "\n\n（注意：diff 过大，已截断，请仅基于可见部分 review）" if truncated else ""
    user_msg = f"{context}以下是 PR 的 git diff：{note}\n\n```diff\n{diff}\n```"

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_msg},
        ],
        "temperature": 0.2,
    }
    req = urllib.request.Request(
        f"{base_url}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"AI review API call failed: {e}", file=sys.stderr)
        return 1

    review = data["choices"][0]["message"]["content"]
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(review)
    print(f"review written to {out_path} ({len(review)} chars)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
