# ══════════════════════════════════════════════════════════════════
# 【W6】RAG 评估脚本 —— 用数字回答"我的检索好不好"
# ══════════════════════════════════════════════════════════════════
#
# 用法（backend 目录下）：
#   python eval.py                      # 单次评估（默认配置）
#   python eval.py --compare            # 对比：有无 rerank × 不同 topK
#   python eval.py --eval-set my.json   # 指定评估集
#
# 评估集格式（JSON 数组）：
#   [{"question": "我哪天要讲课？",
#     "expected_source": "学习计划",     # 命中的块应来自这份文档
#     "expected_keyword": "周三晚上"}]   # 或命中文本含此关键词（二者满足其一即算命中）
#
# ⚠️ 真实评估要用在线 embedding（默认）；离线模式只验证脚本能跑通。

import argparse
import json
import os
import sys

from rag_core import VectorStore, evaluate, get_embedder

BASE_DIR = os.path.dirname(os.path.abspath(__file__))


def load_eval_set(path: str) -> list:
    if not os.path.exists(path):
        print(f"[错误] 评估集不存在: {path}")
        sys.exit(1)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--eval-set",
                        default=os.path.join(BASE_DIR, "eval_set.json"))
    parser.add_argument("--top-k", type=int, default=4)
    parser.add_argument("--compare", action="store_true",
                        help="对比实验：有无 rerank × 不同 topK")
    args = parser.parse_args()

    embedder = get_embedder()
    store = VectorStore(os.path.join(BASE_DIR, "vectors.json"))

    print(f"embedder  = {embedder.name}")
    print(f"知识库    = {len(store.chunks)} 块（库存 embedder: {store.embedder_name}）")
    if store.embedder_name and store.embedder_name != embedder.name:
        print("[警告] 库存向量与当前 embedder 不一致，检索结果会是噪声！")
    if not store.chunks:
        print("[提示] 知识库为空，先调 /ingest 入库再评估")

    items = load_eval_set(args.eval_set)
    print(f"评估集    = {len(items)} 条问题\n")

    if args.compare:
        variants = [
            ("单阶段 (无rerank, Top4)", 4, False),
            ("两阶段 (rerank,   Top1)", 1, True),
            ("两阶段 (rerank,   Top4)", 4, True),
            ("两阶段 (rerank,   Top8)", 8, True),
        ]
        print(f"{'配置':<28} {'HitRate':>8} {'Recall':>8} {'MRR':>8}")
        print("-" * 58)
        for tag, k, use_rerank in variants:
            res = evaluate(items, store, embedder,
                           top_k=k, use_rerank=use_rerank)
            hr = res.get(f"HitRate@{k}", 0)
            rc = res.get(f"Recall@{k}", 0)
            print(f"{tag:<28} {hr:>8} {rc:>8} {res['MRR']:>8}")
        print("\n读法：rerank 让 MRR 上升 = 排序变好；topK 变大 HitRate 升但噪声增多")
    else:
        res = evaluate(items, store, embedder,
                       top_k=args.top_k, use_rerank=True)
        k = args.top_k
        print("评估结果（两阶段检索）")
        print(f"  HitRate@{k} = {res.get(f'HitRate@{k}')}")
        print(f"  Recall@{k}  = {res.get(f'Recall@{k}')}")
        print(f"  MRR        = {res['MRR']}")
        missed = [d["question"] for d in res["details"] if not d["hit"]]
        if missed:
            print(f"  未命中 {len(missed)} 条: {missed}")
        print("\n明细：")
        for d in res["details"]:
            mark = "OK " if d["hit"] else "MISS"
            rank = f"首个命中排名={d['first_hit_rank']}" if d["hit"] else "未命中"
            print(f"  [{mark}] {d['question']}  ({rank})")


if __name__ == "__main__":
    main()
