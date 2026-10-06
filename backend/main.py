# ══════════════════════════════════════════════════════════════════
# 【W5+W6】Workbuddy 后端 —— 备忘录云 + RAG 知识库（可评估版）
# ══════════════════════════════════════════════════════════════════
#
# 架构：本文件只做"HTTP 门面"，RAG 逻辑全在 rag_core.py（可单独测试）
#   main.py     路由层：参数校验 + 调 rag_core
#   rag_core.py 核心层：Embedder / 语义切块 / 向量检索 / Rerank / 评估指标
#   eval.py     评估脚本：跑评估集，输出 HitRate / Recall / MRR
#
# 启动：
#   set DASHSCOPE_API_KEY=sk-xxx
#   uvicorn main:app --host 0.0.0.0 --port 8000
#   # 手机 USB：adb reverse tcp:8000 tcp:8000
#   # 离线测代码：set EMBED_BACKEND=local
#
# 为什么 Android 需要后端（面试必考）：
#   1. 密钥安全：DashScope Key 在后端环境变量，APK 反编译也拿不到
#   2. 跨设备：备忘录上云 = 换手机不丢
#   3. 重活下沉：切块/向量化/检索是存储与计算密集，手机不该干
#   4. 知识私有：私人文档只留在自己的机器上

import os
import sqlite3
import time
from typing import List

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from rag_core import (
    BASE_DIR,
    VectorStore,
    evaluate,
    get_embedder,
    retrieve,
    semantic_chunk,
)

app = FastAPI(title="Workbuddy Backend", version="0.2.0")

DB_PATH = os.path.join(BASE_DIR, "workbuddy.db")
VECTOR_PATH = os.path.join(BASE_DIR, "vectors.json")

embedder = get_embedder()
store = VectorStore(VECTOR_PATH)


# ── 备忘录（SQLite，W5 不变）────────────────────────────────────────

def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with db() as conn:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS memos ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " content TEXT NOT NULL,"
            " created_at REAL NOT NULL)"
        )


init_db()


# ── 请求模型 ─────────────────────────────────────────────────────

class MemoIn(BaseModel):
    content: str


class IngestIn(BaseModel):
    title: str
    text: str
    max_size: int = 400
    overlap: int = 60


class SearchIn(BaseModel):
    query: str
    top_k: int = 4
    coarse_k: int = 20
    use_rerank: bool = True          # W6 开关：关掉 = 退回 W5 单阶段检索


class EvalIn(BaseModel):
    items: List[dict]
    top_k: int = 4
    use_rerank: bool = True


# ── 路由 ─────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status": "ok",
        "embedder": embedder.name,
        "chunks": len(store.chunks),
    }


@app.post("/memos")
def add_memo(memo: MemoIn):
    with db() as conn:
        cur = conn.execute(
            "INSERT INTO memos (content, created_at) VALUES (?, ?)",
            (memo.content.strip(), time.time()),
        )
        return {"id": cur.lastrowid, "saved": True}


@app.get("/memos")
def list_memos():
    with db() as conn:
        rows = conn.execute(
            "SELECT id, content, created_at FROM memos ORDER BY id"
        ).fetchall()
        return {"memos": [dict(r) for r in rows], "total": len(rows)}


@app.post("/ingest")
def ingest(doc: IngestIn):
    """文档入库：语义切块 → 向量化 → 存库。"""
    chunks = semantic_chunk(doc.text, doc.max_size, doc.overlap)
    if not chunks:
        raise HTTPException(400, "文档内容为空")
    vectors = embedder.embed(chunks)
    added = store.add(doc.title, chunks, vectors, embedder)
    return {"chunks": added, "total_chunks": len(store.chunks),
            "embedder": embedder.name}


@app.post("/search")
def search(q: SearchIn):
    """两阶段检索：向量粗排召回 → Rerank 精排。"""
    results = retrieve(
        q.query, store, embedder,
        coarse_k=q.coarse_k, final_k=q.top_k, use_rerank=q.use_rerank,
    )
    return {"results": results, "count": len(results),
            "rerank_applied": q.use_rerank and bool(os.environ.get("DASHSCOPE_API_KEY"))}


@app.post("/eval")
def run_eval(body: EvalIn):
    """跑评估集 —— 没有度量就没有优化。"""
    return evaluate(body.items, store, embedder,
                    top_k=body.top_k, use_rerank=body.use_rerank)


@app.get("/stats")
def stats():
    """知识库概览：来源分布，排查"到底入没入库"。"""
    by_source: dict = {}
    for c in store.chunks:
        by_source[c["source"]] = by_source.get(c["source"], 0) + 1
    return {"total_chunks": len(store.chunks),
            "embedder": store.embedder_name,
            "sources": by_source}
