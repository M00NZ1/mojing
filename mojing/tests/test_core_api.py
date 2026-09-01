"""核心 API 测试集 — 覆盖 10 个最常用接口。"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from fastapi.testclient import TestClient

from backend.app.main import create_app

app = create_app()
client = TestClient(app)


class TestHealth:
    def test_health_check(self):
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json() == {"ok": True}


class TestSingleMachineMode:
    def test_local_api_does_not_require_credentials(self):
        resp = client.get("/api/encyclopedia")
        assert resp.status_code == 200

        stale_token_resp = client.get(
            "/api/encyclopedia",
            headers={"Authorization": "Bearer stale-token"},
        )
        assert stale_token_resp.status_code == 200

    def test_legacy_auth_endpoints_are_not_exposed(self):
        endpoints = (
            ("POST", "/api/auth/register"),
            ("POST", "/api/auth/login"),
            ("POST", "/api/auth/logout"),
            ("GET", "/api/auth/me"),
        )
        for method, path in endpoints:
            resp = client.request(method, path, json={} if method == "POST" else None)
            assert resp.status_code == 404


class TestEncyclopedia:
    def test_list_encyclopedias(self):
        resp = client.get("/api/encyclopedia")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)
        if data:
            assert "name" in data[0]

    def test_list_entries(self):
        resp = client.get("/api/encyclopedia/entries")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)

    def test_entry_types(self):
        resp = client.get("/api/encyclopedia/entry-types")
        assert resp.status_code == 200
        types = resp.json()
        assert isinstance(types, list)
        assert len(types) >= 1

    def test_schema_and_source_policy(self):
        schema_resp = client.get("/api/encyclopedia/schemas")
        assert schema_resp.status_code == 200
        schemas = schema_resp.json()
        assert len(schemas) >= 1

        modules_resp = client.get("/api/encyclopedia/generate-styles")
        assert modules_resp.status_code == 200
        styles = modules_resp.json()
        assert len(styles) >= 1

    def test_bootstrap_template_and_source_preview(self):
        enc_resp = client.post(
            "/api/encyclopedia",
            json={"name": "pytest 百科测试", "description": "测试用百科库"},
        )
        assert enc_resp.status_code == 200
        enc_id = enc_resp.json()["id"]

        # 名称生成测试
        gen_resp = client.post(
            "/api/encyclopedia/generate-names",
            json={"style": "eastern", "name_type": "character", "count": 5},
        )
        assert gen_resp.status_code == 200
        gen_data = gen_resp.json()
        assert len(gen_data["results"]) >= 1

        # 条目创建测试
        entry_resp = client.post("/api/encyclopedia/entries", json={
            "encyclopedia_id": enc_id,
            "title": "测试条目",
            "entry_type": "concept",
            "summary": "用于测试的条目",
            "content": "这是测试内容",
            "tags": "测试",
        })
        assert entry_resp.status_code == 200
        entry_id = entry_resp.json()["id"]

        # 条目详情测试（含关系）
        detail_resp = client.get(f"/api/encyclopedia/entries/{entry_id}")
        assert detail_resp.status_code == 200
        assert detail_resp.json()["entry"]["id"] == entry_id

        # 删除条目测试
        del_resp = client.delete(f"/api/encyclopedia/entries/{entry_id}")
        assert del_resp.status_code == 200

        # 清理百科库
        client.delete(f"/api/encyclopedia/{enc_id}")

        bad_resp = client.post(f"/api/encyclopedia/{enc_id}/import-sources", json={"sources": []})
        assert bad_resp.status_code == 400

    def test_list_sediment_entries_by_encyclopedia(self):
        enc_resp = client.post(
            "/api/encyclopedia",
            json={"name": "pytest 沉淀列表", "description": "sediment list"},
        )
        assert enc_resp.status_code == 200
        enc_id = enc_resp.json()["id"]

        empty = client.get(f"/api/encyclopedia/{enc_id}/sediment-entries")
        assert empty.status_code == 200
        assert empty.json() == []

        entry_resp = client.post(
            "/api/encyclopedia/entries",
            json={
                "encyclopedia_id": enc_id,
                "title": "推断条目",
                "entry_type": "concept",
                "summary": "摘要",
                "content": "",
                "tags": "",
            },
        )
        assert entry_resp.status_code == 200
        entry_id = entry_resp.json()["id"]
        put_resp = client.put(
            f"/api/encyclopedia/entries/{entry_id}/confidence",
            json={"confidence": "inferred"},
        )
        assert put_resp.status_code == 200

        sed = client.get(f"/api/encyclopedia/{enc_id}/sediment-entries")
        assert sed.status_code == 200
        rows = sed.json()
        assert len(rows) >= 1
        assert any(r["id"] == entry_id for r in rows)

        nf = client.get("/api/encyclopedia/999999991/sediment-entries")
        assert nf.status_code == 404

        client.delete(f"/api/encyclopedia/entries/{entry_id}")
        client.delete(f"/api/encyclopedia/{enc_id}")

    def test_curated_source_presets_seeded(self):
        resp = client.get("/api/encyclopedia")
        assert resp.status_code == 200
        data = resp.json()
        by_name = {item["name"]: item for item in data}
        required = [
            "都市 · 原创通用世界",
            "官场 · 原创制度权谋",
            "校园 · 原创青春成长",
            "穿越古代 · 原创架空王朝",
            "武侠江湖 · 原创江湖世界",
            "传统西幻 · 原创冒险大陆",
            "传统古武 · 原创国术演武",
            "古典修仙 · 原创修真界",
            "玄幻世界 · 原创万族大界",
            "洪荒世界 · 原创神话洪荒",
        ]
        for name in required:
            assert name in by_name
            assert by_name[name]["entry_count"] >= 20
            enc_id = by_name[name]["id"]
            entries_resp = client.get(f"/api/encyclopedia/entries?encyclopedia_id={enc_id}")
            assert entries_resp.status_code == 200
            titles = {item["title"] for item in entries_resp.json()}
            assert "一、世界基础" not in titles
            assert "二十、推荐首版必须上线模块" not in titles

        deprecated = [
            "SCP基金会 · 可信来源索引",
            "克苏鲁神话 · Chaosium与公版来源索引",
            "战锤40K · 官方来源索引",
            "星球大战 · 官方Databank索引",
            "传统西幻 · SRD兼容模板",
        ]
        for name in deprecated:
            assert name not in by_name, f"{name} should be deprecated and deleted"

        jianghu_id = by_name["武侠江湖 · 原创江湖世界"]["id"]
        entries_resp = client.get(f"/api/encyclopedia/entries?encyclopedia_id={jianghu_id}")
        assert entries_resp.status_code == 200
        entries = entries_resp.json()
        assert len(entries) >= 20
        assert any("九州江湖地图" == item["title"] for item in entries)
        assert any("武学境界体系" == item["title"] for item in entries)


class TestSystem:
    def test_system_status(self):
        resp = client.get("/api/system/status")
        assert resp.status_code == 200
        data = resp.json()
        assert "python_version" in data

    def test_ai_providers(self):
        resp = client.get("/api/ai/providers")
        assert resp.status_code == 200


class TestWorldGeneration:
    def test_generate_world_uses_public_template(self):
        resp = client.post(
            "/api/worlds/generate",
            json={
                "world_type": "玄幻",
                "core_theme": "失落文明复苏引发势力重组",
                "tone": "严谨、长期推进",
                "lore_entry_count": 1,
                "auto_save": False,
            },
        )
        assert resp.status_code == 200
        data = resp.json()
        titles = {item["title"] for item in data["lore_entries"]}
        assert len(data["lore_entries"]) >= 15
        assert {"世界观总览", "字段模板", "全局搜索"}.issubset(titles)
        assert "用户手动输入" in data["template"]["world_prompt"]


class TestSessions:
    def test_list_sessions(self):
        resp = client.get("/api/sessions")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)
