"""全流程 E2E 测试 v5 — 修正 TestClient 参数问题"""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from fastapi.testclient import TestClient
from backend.app.main import create_app

app = create_app()
client = TestClient(app)

ok = 0
fail = 0
bugs = []

def t(name, method="GET", path="/health", data=None, expect=200):
    global ok, fail
    try:
        if method == "GET":
            r = client.get(path)
        elif method == "POST":
            r = client.post(path, json=data or {})
        elif method == "PUT":
            r = client.put(path, json=data or {})
        elif method == "DELETE":
            r = client.delete(path)
        elif method == "PATCH":
            r = client.patch(path, json=data or {})
        good = r.status_code == expect
        if good:
            ok += 1
            print(f"  ✅ {name} ({r.status_code})")
        else:
            fail += 1
            print(f"  ❌ {name} — 期望 {expect} 实际 {r.status_code}: {r.text[:80]}")
            bugs.append(f"[{r.status_code}] {name}")
        return r
    except Exception as e:
        fail += 1
        print(f"  💥 {name} — {e}")
        bugs.append(f"[EXCEPTION] {name}: {e}")
        return None

cid = None
sid = None

print("=" * 60)
print("全流程 E2E 测试 v5")
print("=" * 60)

print("\n--- 1. 系统基础 ---")
t("健康检查", "GET", "/health")
t("系统状态", "GET", "/api/system/status")
t("可用提供商", "GET", "/api/ai/providers")
t("语音服务配置", "GET", "/api/system/voice-service-config")
t("运行配置", "GET", "/api/system/runtime-config")
t("宏变量列表", "GET", "/api/system/macros")

print("\n--- 2. 单机访问 ---")
t("免登录访问本地 API", "GET", "/api/encyclopedia")
t("注册入口已移除", "POST", "/api/auth/register", {}, expect=404)
t("登录入口已移除", "POST", "/api/auth/login", {}, expect=404)

print("\n--- 3. 百科 ---")
t("百科列表", "GET", "/api/encyclopedia")
t("条目类型", "GET", "/api/encyclopedia/entry-types")
t("全部条目", "GET", "/api/encyclopedia/entries")

print("\n--- 4. 世界模板 ---")
t("模板列表", "GET", "/api/worlds/templates")

print("\n--- 5. 角色管理 ---")
r = t("创建Alice", "POST", "/api/characters", {"name":"AliceE2Ev5","persona_prompt":"你是Alice。","api_key":"sk-test","api_base_url":"https://api.deepseek.com","model_name":"deepseek-chat"})
if r: cid = r.json().get("id")
t("创建Bob", "POST", "/api/characters", {"name":"BobE2Ev5","persona_prompt":"你是Bob。","api_key":"sk-test","api_base_url":"https://api.deepseek.com","model_name":"deepseek-chat"})
if cid:
    t("获取单个角色", "GET", f"/api/characters/{cid}", expect=200)
    t("表情列表", "GET", f"/api/characters/{cid}/expressions")
    t("添加表情", "POST", f"/api/characters/{cid}/expressions", {"expression":"happy","label":"开心"})

print("\n--- 6. 渠道 ---")
t("渠道列表", "GET", "/api/channels")

print("\n--- 7. 会话全流程 ---")
r = t("创建会话", "POST", "/api/sessions", {"title":"E2Ev5"}, expect=200)
if r: sid = r.json().get("id")
if sid:
    t("列出会话", "GET", "/api/sessions")
    t("获取会话详情", "GET", f"/api/sessions/{sid}", expect=200)
    t("更新会话", "PUT", f"/api/sessions/{sid}", {"title":"已更新v5"})
    if cid:
        t("添加参与人", "POST", f"/api/sessions/{sid}/participants", {"character_id": cid})
        t("参与人列表", "GET", f"/api/sessions/{sid}/participants")
    t("世界配置获取", "GET", f"/api/sessions/{sid}/world")
    t("世界配置更新", "PUT", f"/api/sessions/{sid}/world", {"world_prompt":"测试。","narrator_enabled":False})
    t("发送用户消息", "POST", f"/api/sessions/{sid}/user-message", {"content":"你好！"})
    t("消息列表", "GET", f"/api/sessions/{sid}/messages?branch_id=main&page=1&page_size=10")
    t("消息搜索", "GET", f"/api/sessions/{sid}/messages/search?query=你好")
    t("Token用量", "GET", f"/api/sessions/{sid}/token-usage")
    t("分支列表", "GET", f"/api/sessions/{sid}/branches")
    t("角色状态", "GET", f"/api/sessions/{sid}/character-states")
    t("说话人规划", "POST", f"/api/sessions/{sid}/speaker-plan", {"user_input":"你好"})
    t("书签列表", "GET", f"/api/sessions/{sid}/bookmarks")
    t("数据导出", "GET", f"/api/sessions/{sid}/export")
    if cid:
        t("删除参与人", "DELETE", f"/api/sessions/{sid}/participants/{cid}")
    t("删除会话", "DELETE", f"/api/sessions/{sid}")
    t("确认已删除", "GET", f"/api/sessions/{sid}", expect=404)

print("\n--- 8. 系统 ---")
t("系统状态", "GET", "/api/system/status")
t("本地配置", "GET", "/api/system/local-config")
t("宏变量列表", "GET", "/api/system/macros")

print("\n--- 9. 语音 ---")
t("声线列表", "GET", "/api/voices")
t("工作台", "GET", "/api/workbench")

print("\n--- 10. 输出规则 ---")
t("输出规则列表", "GET", "/api/output-rules")
t("添加规则", "POST", "/api/output-rules", {"pattern":r"\*\*","replacement":"","label":"test"})

print("\n--- 11. 监控 ---")
t("成本统计", "GET", "/api/costs")
t("LLM事件", "GET", "/api/tasks/llm-events")
t("动作列表", "GET", "/api/actions")

print("\n" + "=" * 60)
print(f"测试完成: {ok} 通过, {fail} 失败")
if bugs:
    print(f"\n⚠️  问题:")
    for i, b in enumerate(bugs, 1):
        print(f"  {i}. {b}")
else:
    print("✅ 全部通过！")
print("=" * 60)
