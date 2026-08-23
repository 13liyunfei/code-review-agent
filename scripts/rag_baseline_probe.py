#!/usr/bin/env python3
"""
RAG 优化前后对比探针 —— BASELINE 复现（master 纯向量检索）。
方法：从 PG 取全部 RAG 知识块（id + embedding + content），在 Python 内用同一
TokenHub 向量对查询做余弦相似度计算并重排，完整复现 master 的「纯向量 + TopN」链路
（无 BM25 / 无重排器 / 无阈值过滤）。结果用于与优化后（混合检索+阈值+重排）对比。
"""
import os, json, math, subprocess, requests

KEY = os.environ.get("TOKENHUB_API_KEY", "sk-8p8DUUQrEr7dpCdGWc7IUUaqNaSXzlWsOuGn2rUzMrxQcXdC")
BASE = "https://tokenhub.tencentmaas.com/v1"
MODEL = "kinfra-text-embedding-0.6b"
PSQL = "/opt/homebrew/opt/postgresql@17/bin/psql"
PROXY = os.environ.get("HTTPS_PROXY")
PROXIES = {"https": PROXY, "http": PROXY} if PROXY else None

QUERIES = {
    "SQL注入/拼接": "String sql = \"SELECT * FROM users WHERE id=\"+id 外部输入拼接 SQL 语句 参数化查询",
    "硬编码凭证": "String password = \"s3cret123\" 硬编码 API Key 密码 Token 敏感凭证 KMS",
    "System.out打印": "System.out.println 生产代码禁止使用 System.out 输出 统一日志",
    "e.printStackTrace": "catch(Exception e){ e.printStackTrace() } 空 catch 块吞掉异常 日志记录",
    "TODO标记": "代码中遗留 TODO FIXME 未跟踪标记",
}


def embed(text):
    r = requests.post(f"{BASE}/embeddings",
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"},
        json={"model": MODEL, "input": text}, proxies=PROXIES, timeout=30)
    r.raise_for_status()
    return r.json()["data"][0]["embedding"]


def fetch_chunks():
    """取所有 RAG 块：id, embedding(文本), content(去换行)。"""
    sql = ("SELECT id, embedding::text, replace(left(content,50), E'\n',' ') "
           "FROM memory_store WHERE agent_type='RAG'")
    out = subprocess.run([PSQL, "-d", "codereview", "-U", "yunfei", "-h", "/tmp",
                          "-t", "-A", "-F", "\x01", "-c", sql],
                         capture_output=True, text=True, timeout=120)
    chunks = []
    for line in out.stdout.strip().splitlines():
        p = line.split("\x01")
        if len(p) >= 3:
            try:
                vec = [float(x) for x in p[1].strip("[]").split(",")]
                chunks.append((int(p[0]), vec, p[2]))
            except ValueError:
                pass
    return chunks


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def main():
    chunks = fetch_chunks()
    print(f"[*] 已加载 RAG 知识块 {len(chunks)} 条")
    print("=" * 72)
    print("BASELINE 复现（master 纯向量检索：无 BM25 / 无重排 / 无阈值）")
    print("=" * 72)
    res = {}
    for name, q in QUERIES.items():
        qv = embed(q)
        scored = [(cid, txt, round(cosine(qv, vec), 4))
                  for cid, vec, txt in chunks]
        scored.sort(key=lambda x: x[2], reverse=True)
        top = scored[:10]
        res[name] = [[c, t, s] for c, t, s in top]
        maxsim = top[0][2] if top else 0.0
        print(f"\n### {name}  (候选={len(top)}, maxSim={maxsim:.4f})")
        if not top:
            print("   (无候选)")
            continue
        for i, (cid, txt, sim) in enumerate(top[:5], 1):
            print(f"   [{i}] sim={sim:.4f} id={cid} {txt}")
    with open("/tmp/rag_baseline_probe.json", "w") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    print("\n[OK] -> /tmp/rag_baseline_probe.json")


if __name__ == "__main__":
    main()
