# ══════════════════════════════════════════════════════════════════
# 【W6】离线冒烟测试 —— 没有 API Key 也能验证整条 RAG 流水线
# ══════════════════════════════════════════════════════════════════
#
# 运行（backend 目录）：
#   python tests/smoke_test.py
#
# 用的是 LocalHashEmbedder（字面 bigram 哈希），它不懂语义，
# 所以断言只验"流水线通不通、接口对不对"，不验"检索准不准"。
# 这就是可测性的价值：外部依赖（在线 API）被抽象成接口后，
# 核心逻辑可以在完全离线的环境里被验证。

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from rag_core import (  # noqa: E402
    LocalHashEmbedder,
    VectorStore,
    evaluate,
    retrieve,
    semantic_chunk,
)

DOC_PLAN = """学习计划 v1

周一到周五白天上课，晚上自习。周三晚上要讲课，内容是 LangGraph 的 StateGraph 与条件边。

端侧模型方案先用 MNN，理由是 Android 端推理生态成熟；备选 ONNX Runtime Mobile。
每周日复盘一次，记录当周进度与卡点。"""

DOC_GOAL = """求职目标

目标年薪 60 万，当前 36 万。主打差异化：Android 工程经验 + 大模型应用能力。
作品集核心是一个端云混合的 AI 助手 App。"""


def main():
    embedder = LocalHashEmbedder()
    print(f"[1] embedder = {embedder.name}, dim = {embedder.dim}")

    vecs = embedder.embed(["深圳今天下雨", "鹏城有降雨"])
    assert len(vecs) == 2 and len(vecs[0]) == embedder.dim
    print("[2] embed 输出维度正确")

    chunks = semantic_chunk(DOC_PLAN, max_size=60, overlap=10)
    assert chunks, "切块结果为空"
    assert all(len(c) <= 60 + 10 for c in chunks), "存在超长块"
    print(f"[3] 语义切块 OK：{len(chunks)} 块，最长 {max(len(c) for c in chunks)} 字")
    print("     首块预览:", chunks[0][:30].replace("\n", " "), "...")

    with tempfile.TemporaryDirectory() as tmp:
        store = VectorStore(os.path.join(tmp, "vectors.json"))
        for title, text in [("学习计划", DOC_PLAN), ("求职目标", DOC_GOAL)]:
            cs = semantic_chunk(text, max_size=60, overlap=10)
            store.add(title, cs, embedder.embed(cs), embedder)
        print(f"[4] 入库 OK：共 {len(store.chunks)} 块")

        results = store.search(embedder.embed(["周三晚上讲课"])[0], top_k=3)
        assert results, "检索无结果"
        assert "score" in results[0] and "text" in results[0]
        print(f"[5] 向量检索 OK：Top1 来源={results[0]['source']} 分数={results[0]['score']}")

        # 换 embedder 后维度不一致必须报错，而不是默默返回噪声
        try:
            store.search([0.1] * 8, top_k=1)
            raise AssertionError("维度不匹配竟然没报错")
        except RuntimeError as e:
            print(f"[6] 维度校验 OK：{str(e)[:40]}...")

        got = retrieve("MNN 端侧方案", store, embedder,
                       coarse_k=5, final_k=2, use_rerank=False)
        assert len(got) <= 2
        print(f"[7] 两阶段检索入口 OK（离线时 rerank 自动降级）：{len(got)} 条")

        res = evaluate(
            [{"question": "周三晚上要干什么", "expected_source": "学习计划"},
             {"question": "目标年薪", "expected_keyword": "60"}],
            store, embedder, top_k=3, use_rerank=False,
        )
        assert "MRR" in res and "details" in res
        print(f"[8] 评估指标 OK：HitRate@3={res['HitRate@3']} MRR={res['MRR']}")

    print("\n全部通过 ✅  流水线可运行（语义精度需在线 embedding 才能真正评估）")


if __name__ == "__main__":
    main()
