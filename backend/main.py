# ══════════════════════════════════════════════════════════════════
# 【W5】Workbuddy 后端 —— 备忘录云 + RAG 知识库
# ══════════════════════════════════════════════════════════════════
#
# 为什么 Android 需要 FastAPI 后端？（面试必考）
#   1. 密钥安全：DashScope Key 放后端，APK 里只留内网地址（W1 就提过）
#   2. 跨设备：备忘录存手机本地 = 换手机就没了；上云 = 多端同步
#   3. 重活下沉：切块、向量化、检索都是 CPU/存储密集型，手机干不动也不该干
#   4. 知识私有：个人文档不能发给第三方模型训练，只能本地/私有云持有
#
# 技术选型（刻意从简，教学优先）：
#   SQLite       —— Python 自带，单文件数据库，零部署
#   DashScope embeddings —— 复用你已有的 Key，text-embedding-v3 模型
#   numpy 余弦相似度 —— 向量检索的本质就是"算距离取 TopK"，
#                      不用 FAISS 才能看清它的真面目（W6 换真向量库）
#
# 启动：
#   pip install -r requirements.txt
#   uvicorn main:app --host 0.0.0.0 --port 8000
#   # 手机连电脑 USB 时可执行: adb reverse tcp:8000 tcp:8000
#   # 然后安卓端 BASE_URL 用 http://127.0.0.1:8000

import json
import os
import sqlite3
import time
from typing import List

import numpy as np
import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="Workbuddy Backend", version="0.1.0")

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "workbuddy.db")
VECTOR_PATH = os.path.join(BASE_DIR, "vectors.json")

DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
EMBED_MODEL = "text-embedding-v3"
EMBED_DIM = 1024

# API Key 从环境变量读（别写死在代码里——这是 W1 的教训升级版）
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")


# ── 数据层：SQLite（备忘录）+ JSON（向量库）────────────────────────

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


def load_vectors() -> List[dict]:
    if not os.path.exists(VECTOR_PATH):
        return []
    with open(VECTOR_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def save_vectors(chunks: List[dict]):
    with open(VECTOR_PATH, "w", encoding="utf-8") as f:
        json.dump(chunks, f, ensure_ascii=False)


# ── Embedding：文本 → 1024 维向量 ─────────────────────────────────
# 本质：把一段话变成高维空间里的一个"点"，语义相近的文本，点离得近。
# "深圳今天下雨" 和 "鹏城有降雨" 字面完全不同，但向量距离很近 ——
# 这就是传统关键词搜索做不到的"语义检索"。

def embed(texts: List[str]) -> List[List[float]]:
    if not API_KEY:
        raise HTTPException(500, "未设置 DASHSCOPE_API_KEY 环境变量")
    vectors: List[List[float]] = []
    # text-embedding-v3 单次最多 10 条，分批
    for i in range(0, len(texts), 10):
        batch = texts[i:i + 10]
        resp = requests.post(
            f"{DASHSCOPE_BASE}/embeddings",
            headers={"Authorization": f"Bearer {API_KEY}"},
            json={"model": EMBED_MODEL, "input": batch},
            timeout=30,
        )
        if resp.status_code != 200:
            raise HTTPException(500, f"embedding 调用失败: {resp.text[:200]}")
        data = sorted(resp.json()["data"], key=lambda d: d["index"])
        vectors.extend(d["embedding"] for d in data)
    return vectors


# ── 切块（Chunking）：长文档 → 小段落 ────────────────────────────
# 为什么切？① embedding 对超长文本会"稀释"语义；② 检索要喂给模型的
# 是"相关的那几段"，不是全文（上下文窗口是稀缺资源，W3 思考题的呼应）。
# 固定长度 + 重叠窗口是最朴素的策略，W6 可以对比"按段落语义切"。

def chunk_text(text: str, size: int = 300, overlap: int = 50) -> List[str]:
    text = text.strip()
    if len(text) <= size:
        return [text] if text else []
    chunks = []
    step = size - overlap
    for start in range(0, len(text), step):
        piece = text[start:start + size]
        if len(piece) >= 30:          # 太短的尾巴丢弃
            chunks.append(piece)
    return chunks


# ── API 模型 ─────────────────────────────────────────────────────

class MemoIn(BaseModel):
    content: str


class IngestIn(BaseModel):
    title: str
    text: str


class SearchIn(BaseModel):
    query: str
    top_k: int = 4


# ── 路由：健康检查 / 备忘录 / 知识库 ─────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "chunks": len(load_vectors())}


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
    """文档入库：切块 → 向量化 → 存库。返回切了几块。"""
    chunks = chunk_text(doc.text)
    if not chunks:
        raise HTTPException(400, "文档内容为空")
    vectors = embed(chunks)
    store = load_vectors()
    base_id = int(time.time() * 1000)
    for idx, (text, vec) in enumerate(zip(chunks, vectors)):
        store.append({
            "id": f"{base_id}-{idx}",
            "source": doc.title,
            "text": text,
            "vector": vec,
        })
    save_vectors(store)
    return {"chunks": len(chunks), "total_chunks": len(store)}


@app.post("/search")
def search(q: SearchIn):
    """RAG 的核心一步：查也向量化 → 和所有块算余弦 → 取 TopK。"""
    store = load_vectors()
    if not store:
        return {"results": [], "note": "知识库为空，先 POST /ingest 入库"}
    qvec = np.array(embed([q.query])[0], dtype=np.float32)
    mat = np.array([c["vector"] for c in store], dtype=np.float32)
    # 余弦相似度 = 点积 / (模长×模长)。向量为单位化后的通用写法。
    sims = mat @ qvec / (np.linalg.norm(mat, axis=1) * np.linalg.norm(qvec) + 1e-8)
    top_idx = np.argsort(sims)[::-1][: q.top_k]
    return {
        "results": [
            {
                "score": round(float(sims[i]), 4),
                "source": store[i]["source"],
                "text": store[i]["text"],
            }
            for i in top_idx
        ]
    }
