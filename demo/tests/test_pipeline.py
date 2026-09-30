"""
完整端到端测试 — 验证全部 5 个模块
用法: 先往 data/sample_photos/ 放照片，然后运行:
      conda activate dot
      python tests/test_pipeline.py
"""

import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

SAMPLE_DIR = os.path.join(os.path.dirname(__file__), "..", "data", "sample_photos")


def test_module1_features():
    """模块1: 基础特征提取"""
    print("\n" + "=" * 50)
    print("[测试 模块1] 基础特征提取")
    print("=" * 50)

    from agent.features import extract_features, batch_extract

    # 单张测试
    images = [f for f in os.listdir(SAMPLE_DIR)
              if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))]
    if not images:
        print("  ⚠ 没有测试图片, 请先放入 data/sample_photos/")
        return False

    # 单张提取 (跳过打不开的)
    first_valid = None
    for img_name in images:
        path = os.path.join(SAMPLE_DIR, img_name)
        feat = extract_features(path)
        if feat is not None:
            first_valid = feat
            break
        else:
            print(f"  ⚠ 跳过不可读的文件: {img_name}")

    if first_valid is None:
        print("  ✗ 所有图片都无法读取")
        return False
    feat = first_valid

    print(f"  ✓ 提取成功: {feat.file_name}")
    print(f"    sharpness={feat.sharpness:.3f}, brightness={feat.brightness:.3f}")
    print(f"    contrast={feat.contrast:.3f}, highlight={feat.highlight_clip:.3f}")
    print(f"    shadow={feat.shadow_clip:.3f}, has_face={feat.has_face}")
    print(f"    phash={feat.phash[:16]}...")

    # 批量提取
    features_list = batch_extract(SAMPLE_DIR)
    print(f"  ✓ 批量提取: {len(features_list)} 张")

    # 验证所有字段
    has_face_count = sum(1 for f in features_list if f.has_face)
    print(f"    有人脸: {has_face_count}, 无人脸: {len(features_list) - has_face_count}")

    for feat in features_list:
        assert 0 <= feat.sharpness <= 1, f"sharpness out of range: {feat.sharpness}"
        assert 0 <= feat.brightness <= 1
        assert 0 <= feat.contrast <= 1
        assert 0 <= feat.highlight_clip <= 1
        assert 0 <= feat.shadow_clip <= 1
        assert feat.phash, "phash empty"
        assert feat.face_count >= 0
        assert feat.face_count == len(feat.face_boxes)

    print("  ✓ 所有字段合法")
    return features_list


def test_module1_quality(features_list):
    """模块1: 质量评分"""
    print("\n" + "=" * 50)
    print("[测试 模块1-2] 技术质量评分")
    print("=" * 50)

    from agent.quality_scorer import batch_score

    qualities = batch_score(features_list)
    print(f"  ✓ 评分完成: {len(qualities)} 张")

    # 检查前几张
    for i, (aid, q) in enumerate(list(qualities.items())[:3]):
        feat = next(f for f in features_list if f.asset_id == aid)
        print(f"    {feat.file_name}: score={q.overall_score:.3f}, "
              f"conf={q.confidence:.2f}, reasons={q.reasons}")
        assert 0 <= q.overall_score <= 1

    return qualities


def test_module2_clustering(features_list):
    """模块2: 聚类分组"""
    print("\n" + "=" * 50)
    print("[测试 模块2] 相似照片聚类分组")
    print("=" * 50)

    from agent.clustering import cluster_photos

    groups = cluster_photos(features_list)
    print(f"  ✓ 聚类完成: {len(groups)} 组")

    # 按人脸/无人脸统计
    face_groups = sum(1 for g in groups if g.has_face)
    noface_groups = len(groups) - face_groups
    print(f"    有人脸组: {face_groups}, 无人脸组: {noface_groups}")

    for g in groups[:5]:
        print(f"    {g.group_id}: {len(g.asset_ids)} 张, "
              f"置信度={g.confidence:.2f}, 原因={g.reasons}")
        assert g.asset_ids, "组不能为空"

    groups_dict = {g.group_id: g for g in groups}
    return groups_dict


def test_module3_face(features_list):
    """模块3: 人脸细节分析"""
    print("\n" + "=" * 50)
    print("[测试 模块3] 人脸细节分析")
    print("=" * 50)

    from agent.face_analysis import batch_analyze

    face_ids = [f.asset_id for f in features_list if f.has_face]
    if not face_ids:
        print("  ⚠ 没有含人脸的图片, 跳过人脸分析测试")
        return {}

    asset_paths = {f.asset_id: f.file_path for f in features_list}
    face_boxes = {f.asset_id: f.face_boxes for f in features_list}
    faces = batch_analyze(face_ids, asset_paths, face_boxes)
    print(f"  ✓ 人脸分析完成: {len(faces)} 张")

    for aid, fa in list(faces.items())[:3]:
        print(f"    {aid}: {fa.face_count} 张人脸")
        for face in fa.faces[:2]:
            print(f"      人脸{face.face_id}: emotion={face.emotion}, "
                  f"eyes_open={face.eyes_open}, is_smiling={face.is_smiling}, "
                  f"frontal={face.is_frontal}")
        assert fa.face_count == len(fa.faces)

    return faces


def test_module4_recommend(features_list, qualities, groups, faces):
    """模块4: 组内推荐"""
    print("\n" + "=" * 50)
    print("[测试 模块4] 组内推荐 (MMR)")
    print("=" * 50)

    from agent.scorer import batch_recommend
    from agent.schemas import UserWeights

    features = {f.asset_id: f for f in features_list}
    weights = UserWeights(sharpness=0.30, eyes_open=0.25,
                          expression=0.20, exposure=0.15, composition=0.10)

    recs = batch_recommend(groups, features, qualities, faces, weights)
    print(f"  ✓ 推荐完成: {len(recs)} 组")

    total_keep = 0
    for gid, rec in list(recs.items())[:5]:
        keeps = [r for r in rec.recommendations if r.state == "keep"]
        total_keep += len(keeps)
        print(f"    {gid}: 推荐保留 {len(keeps)}/{len(rec.recommendations)} 张")
        if keeps:
            top = keeps[0]
            print(f"      最佳: {top.asset_id[:12]} score={top.score:.3f}, "
                  f"原因={top.reasons}")

    print(f"  总计推荐保留: {total_keep} 张")
    return recs


def test_module5_llm():
    """模块5: LLM 策略解析 (只测试降级路径, 不带 API key 也能过)"""
    print("\n" + "=" * 50)
    print("[测试 模块5] 自然语言策略解析 (LLM)")
    print("=" * 50)

    from agent.llm_provider import parse_instruction

    # 测试降级路径 (没配 key 会自动降级)
    strategy = parse_instruction("每组保留两张，优先清晰和睁眼的照片")
    print(f"  ✓ LLM 解析结果:")
    print(f"    keep_per_group={strategy.keep_per_group}")
    print(f"    strictness={strategy.strictness}")
    print(f"    explanation={strategy.explanation}")

    # 检查字段完整性
    assert strategy.keep_per_group is not None
    assert strategy.explanation, "explanation 不能为空"
    print(f"  ✓ LLM 模块正常 (如已配置 API key 会调用真实 API)")

    return strategy


if __name__ == "__main__":
    print("=" * 50)
    print("  图片筛选系统 — 端到端测试")
    print("=" * 50)

    if not os.path.isdir(SAMPLE_DIR):
        print(f"\n[错误] 目录不存在: {SAMPLE_DIR}")
        print("请先创建目录并放入照片")
        sys.exit(1)

    # 模块1
    features_list = test_module1_features()
    if not features_list:
        sys.exit(1)

    qualities = test_module1_quality(features_list)

    # 模块2
    groups = test_module2_clustering(features_list)

    # 模块3
    faces = test_module3_face(features_list)

    # 模块4
    recs = test_module4_recommend(features_list, qualities, groups, faces)

    # 模块5
    strategy = test_module5_llm()

    print("\n" + "=" * 50)
    print("  ✅ 全部 5 个模块测试通过!")
    print("=" * 50)
    print()
    print("提示: 如配置了 SOPHNET_API_KEY, LLM 模块会调用真实 API")
    print("启动方式: cd demo && python backend/app.py")
