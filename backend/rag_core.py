# ══════════════════════════════════════════════════════════════════
# 【W6】RAG 核心模块 —— 从"能跑"到"可评估、可优化"
# ══════════════════════════════════════════════════════════════════
#
# W5 的 main.py 把一切塞在一起；W6 把"可优化的部分"抽出来独立成模块，
# 每个模块都留了一个"可替换点"——这才是工程化 RAG 的样子：
#
#   ① Embedder   可插拔：在线 DashScope / 离线 LocalHash（测试用）
#   ② 切块策略   固定长度 → 语义切块（段落/句子边界优先）
#   ③ 检索       单阶段 → 两阶段（粗排 TopK=20 → 精排 Rerank TopK=4）
#   ④ 评估       HitRate / Recall@K / MRR（没有度量就没有优化）
#
# 为什么要两阶段检索？（面试高频）
#   向量检索是"双编码器"：query 和 doc 各自独立编码，快但精度有限，
#   容易把"字面相近但答非所问"的块排前面；
#   Rerank 是"交叉编码器"：把 query 和 doc 一起喂进模型打分，准但慢。
#   所以工业界标准做法：向量粗排捞一大批（召回优先），Rerank 精排出最终几个。

import hashlib
import json
import math
import os
import re
import time
from typing import List, Optional, Protocol

try:
    import numpy as np
except ImportError:          # 依赖降级：没有 numpy 也能跑（纯 Python 慢一点）
    np = None

# requests 只在"在线路径"用到（DashScope embedding / rerank），
# 所以延迟到函数内部导入 —— 离线跑测试时不必安装它。

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
RERANK_URL = (
    "https://dashscope.aliyuncs.com/api/v1/services/rerank/"
    "text-rerank/text-rerank"
)
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")


# ══════════════════════════════════════════════════════════════════
# ① Embedder 抽象：把"文本→向量"这件事做成可替换的
# ══════════════════════════════════════════════════════════════════

class Embedder(Protocol):
    name: str
    dim: int

    def embed(self, texts: List[str]) -> List[List[float]]: ...


class DashScopeEmbedder:
    """生产用：text-embedding-v3，1024 维，语义能力强。"""

    name = "dashscope-text-embedding-v3"
    dim = 1024

    def embed(self, texts: List[str]) -> List[List[float]]:
        import requests
        if not API_KEY:
            raise RuntimeError("未设置 DASHSCOPE_API_KEY 环境变量")
        vectors: List[List[float]] = []
        for i in range(0, len(texts), 10):          # 单次最多 10 条
            batch = texts[i:i + 10]
            resp = requests.post(
                f"{DASHSCOPE_BASE}/embeddings",
                headers={"Authorization": f"Bearer {API_KEY}"},
                json={"model": "text-embedding-v3", "input": batch},
                timeout=30,
            )
            if resp.status_code != 200:
                raise RuntimeError(f"embedding 失败: {resp.text[:200]}")
            data = sorted(resp.json()["data"], key=lambda d: d["index"])
            vectors.extend(d["embedding"] for d in data)
        return vectors


class LocalHashEmbedder:
    """
    离线兜底：字符 bigram 哈希到固定维度后 L2 归一化。
    它不懂语义（"鹏城"和"深圳"距离很远），但能让整条流水线
    在没有 API Key / 断网时跑通 —— 用来测代码、跑单元测试。
    这本身就是个教学点：把外部依赖做成可替换接口，代码才可测。
    """

    name = "local-hash"
    dim = 512

    def embed(self, texts: List[str]) -> List[List[float]]:
        out = []
        for text in texts:
            vec = [0.0] * self.dim
            for i in range(len(text) - 1):
                gram = text[i:i + 2]
                h = int(hashlib.md5(gram.encode("utf-8")).hexdigest()[:8], 16)
                vec[h % self.dim] += 1.0
            norm = math.sqrt(sum(v * v for v in vec))
            out.append([v / norm for v in vec] if norm > 0 else vec)
        return out


def get_embedder() -> Embedder:
    """EMBED_BACKEND=local 时用离线兜底（测代码用），默认在线。"""
    if os.environ.get("EMBED_BACKEND", "").lower() == "local":
        return LocalHashEmbedder()
    return DashScopeEmbedder()


# ══════════════════════════════════════════════════════════════════
# ② 切块：从"数着字数硬切"升级为"顺着语义边界切"
# ══════════════════════════════════════════════════════════════════

_SENTENCE_END = re.compile(r"(?<=[。！？；!?;\n])")


def semantic_chunk(text: str, max_size: int = 400, overlap: int = 60) -> List[str]:
    """
    两级策略（都是工程里的常规做法）：
      1. 段落优先：空行是天然的语义边界，先按段落分
      2. 超长段落：按句末标点切成句子，再贪心合并到接近 max_size
    重叠 overlap：相邻块共享尾部若干字，避免"答案正好被切在边界上"
    """
    text = (text or "").strip()
    if not text:
        return []

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    chunks: List[str] = []

    for para in paragraphs:
        if len(para) <= max_size:
            chunks.append(para)
            continue
        # 句子级合并
        sentences = [s for s in _SENTENCE_END.split(para) if s.strip()]
        buf = ""
        for sent in sentences:
            if len(buf) + len(sent) <= max_size:
                buf += sent
            else:
                if buf:
                    chunks.append(buf.strip())
                    # 与上一块共享尾部（语义衔接）
                    tail = buf[-overlap:] if overlap > 0 else ""
                    buf = tail + sent
                else:
                    # 单句就超长：退化为硬切
                    for start in range(0, len(sent), max_size - overlap):
                        chunks.append(sent[start:start + max_size])
                    buf = ""
        if buf.strip():
            chunks.append(buf.strip())

    return [c for c in chunks if len(c) >= 20]


# ══════════════════════════════════════════════════════════════════
# ③ 向量存储 + 两阶段检索
# ══════════════════════════════════════════════════════════════════

class VectorStore:
    """
    文件版向量库：{embedder, dim, chunks:[{id, source, text, vector}]}
    带上 embedder 元信息是刻意的 —— 换 embedding 模型必须重建库，
    否则向量空间不一致，检索结果就是噪声（生产事故常见原因）。
    """

    def __init__(self, path: str):
        self.path = path
        self.chunks: List[dict] = []
        self.embedder_name: Optional[str] = None
        self.load()

    def load(self):
        if not os.path.exists(self.path):
            return
        with open(self.path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, list):          # 兼容 W5 的老格式
            self.chunks = data
            self.embedder_name = None
        else:
            self.chunks = data.get("chunks", [])
            self.embedder_name = data.get("embedder")

    def save(self):
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump(
                {"embedder": self.embedder_name, "chunks": self.chunks},
                f, ensure_ascii=False,
            )

    def add(self, source: str, texts: List[str], vectors: List[List[float]],
            embedder: Embedder):
        self.embedder_name = embedder.name
        base = int(time.time() * 1000)
        for i, (text, vec) in enumerate(zip(texts, vectors)):
            self.chunks.append({
                "id": f"{base}-{i}",
                "source": source,
                "text": text,
                "vector": vec,
            })
        self.save()
        return len(texts)

    def search(self, query_vec: List[float], top_k: int) -> List[dict]:
        if not self.chunks:
            return []
        dims = {len(c["vector"]) for c in self.chunks}
        if len(query_vec) not in dims:
            raise RuntimeError(
                f"维度不匹配：库存 {sorted(dims)} 维，查询 {len(query_vec)} 维。"
                "换过 embedding 模型？用相同 embedder 重建知识库。"
            )
        scored = _cosine_scores(query_vec, [c["vector"] for c in self.chunks])
        order = sorted(range(len(scored)), key=lambda i: scored[i], reverse=True)[:top_k]
        return [
            {"score": round(scored[i], 4),
             "source": self.chunks[i]["source"],
             "text": self.chunks[i]["text"]}
            for i in order
        ]


def _cosine_scores(q: List[float], mat: List[List[float]]) -> List[float]:
    """余弦相似度 = 点积 / (模长×模长)。有 numpy 走矩阵运算，没有就纯 Python。"""
    if np is not None:
        m = np.array(mat, dtype=np.float32)
        qv = np.array(q, dtype=np.float32)
        sims = m @ qv / (np.linalg.norm(m, axis=1) * np.linalg.norm(qv) + 1e-8)
        return [float(s) for s in sims]
    q_norm = math.sqrt(sum(v * v for v in q)) or 1e-8
    out = []
    for row in mat:
        dot = sum(a * b for a, b in zip(row, q))
        row_norm = math.sqrt(sum(v * v for v in row)) or 1e-8
        out.append(dot / (row_norm * q_norm))
    return out


def rerank(query: str, docs: List[dict], top_k: int) -> List[dict]:
    """
    精排：交叉编码器给"query×doc"直接打分。
    在线用 DashScope gte-rerank-v2；没有 Key 就降级返回粗排结果
    （降级而不是报错 —— 生产环境的容错习惯）。
    """
    if not docs or not API_KEY:
        return docs[:top_k]
    try:
        import requests
        resp = requests.post(
            RERANK_URL,
            headers={"Authorization": f"Bearer {API_KEY}",
                     "Content-Type": "application/json"},
            json={
                "model": "gte-rerank-v2",
                "input": {"query": query,
                          "documents": [d["text"] for d in docs]},
                "parameters": {"top_n": top_k, "return_documents": False},
            },
            timeout=30,
        )
        if resp.status_code != 200:
            return docs[:top_k]
        results = resp.json()["output"]["results"]
        out = []
        for r in results[:top_k]:
            d = dict(docs[r["index"]])
            d["rerank_score"] = round(float(r["relevance_score"]), 4)
            out.append(d)
        return out
    except Exception:
        return docs[:top_k]


def retrieve(
    query: str,
    store: VectorStore,
    embedder: Embedder,
    coarse_k: int = 20,
    final_k: int = 4,
    use_rerank: bool = True,
) -> List[dict]:
    """两阶段检索入口：粗排召回 → 精排定序。"""
    qvec = embedder.embed([query])[0]
    coarse = store.search(qvec, coarse_k)
    if use_rerank and len(coarse) > final_k:
        return rerank(query, coarse, final_k)
    return coarse[:final_k]


# ══════════════════════════════════════════════════════════════════
# ④ 评估指标：没有度量就没有优化
# ══════════════════════════════════════════════════════════════════

def _is_hit(item: dict, retrieved: List[dict]) -> bool:
    """命中判定：来源匹配 或 期望关键词出现在检索文本里。"""
    src = item.get("expected_source")
    kw = item.get("expected_keyword")
    for r in retrieved:
        if src and src in r["source"]:
            return True
        if kw and kw in r["text"]:
            return True
    return False


def evaluate(
    items: List[dict],
    store: VectorStore,
    embedder: Embedder,
    top_k: int = 4,
    use_rerank: bool = True,
) -> dict:
    """
    指标口径（面试要说清）：
      HitRate@K  只要 TopK 里有正确的，就算这次检索成功
      Recall@K   正确块被召回的比例（一个问题可能对应多个正确块）
      MRR        第一个正确结果排名的倒数均值（越靠前越好，衡量排序质量）
    """
    if not items:
        return {"error": "评估集为空"}

    hits, recalls, rrs, details = 0, [], [], []
    for item in items:
        got = retrieve(
            item["question"], store, embedder,
            coarse_k=max(20, top_k), final_k=top_k, use_rerank=use_rerank,
        )
        hit = _is_hit(item, got)
        hits += 1 if hit else 0
        recalls.append(1.0 if hit else 0.0)

        rr = 0.0
        for rank, r in enumerate(got, start=1):
            if _is_hit(item, [r]):
                rr = 1.0 / rank
                break
        rrs.append(rr)
        details.append({
            "question": item["question"],
            "hit": hit,
            "first_hit_rank": int(1 / rr) if rr > 0 else None,
            "top_source": got[0]["source"] if got else None,
        })

    n = len(items)
    return {
        "n": n,
        "top_k": top_k,
        "use_rerank": use_rerank,
        f"HitRate@{top_k}": round(hits / n, 4),
        f"Recall@{top_k}": round(sum(recalls) / n, 4),
        "MRR": round(sum(rrs) / n, 4),
        "details": details,
    }
