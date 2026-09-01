from __future__ import annotations

from ..schemas import ProviderPreset


PROVIDER_CATALOG: list[ProviderPreset] = [
    ProviderPreset(
        provider_id="deepseek_official",
        label="DeepSeek 官方",
        base_url="https://api.deepseek.com",
        notes="适合纯文本剧情推进，价格通常较低，但当前不适合作为看图主力。",
        models=[
            {
                "id": "deepseek-chat",
                "label": "deepseek-chat",
                "recommended": True,
                "context_note": "适合常规剧情对话。",
            },
            {
                "id": "deepseek-reasoner",
                "label": "deepseek-reasoner",
                "recommended": False,
                "context_note": "适合复杂推理，但速度和成本会更高。",
            },
        ],
    ),
    ProviderPreset(
        provider_id="siliconflow",
        label="SiliconFlow",
        base_url="https://api.siliconflow.cn/v1",
        notes="适合低成本接多种开源模型，也适合给多人物分发独立 Key。",
        models=[
            {
                "id": "Qwen/Qwen2.5-7B-Instruct",
                "label": "Qwen2.5-7B-Instruct",
                "recommended": True,
                "context_note": "轻量便宜，适合作为配角或摘要器。",
            },
            {
                "id": "Qwen/Qwen2.5-VL-7B-Instruct",
                "label": "Qwen2.5-VL-7B-Instruct",
                "supports_vision": True,
                "recommended": True,
                "context_note": "适合作为看图角色或背景分析器。",
            },
            {
                "id": "deepseek-ai/DeepSeek-V3",
                "label": "DeepSeek-V3",
                "recommended": True,
                "context_note": "适合主角剧情对话。",
            },
        ],
    ),
    ProviderPreset(
        provider_id="volcengine_ark",
        label="火山引擎 Ark",
        base_url="https://ark.cn-beijing.volces.com/api/v3",
        notes="适合需要国产生态、语音产品线与看图能力一起接入的场景。",
        models=[
            {
                "id": "doubao-seed-1-6-flash-250715",
                "label": "Doubao Seed 1.6 Flash",
                "supports_vision": True,
                "recommended": True,
                "context_note": "适合作为剧情主模型和看图输入。",
            },
            {
                "id": "doubao-1-5-lite-32k-250115",
                "label": "Doubao 1.5 Lite 32K",
                "recommended": True,
                "context_note": "适合作为低成本群聊角色。",
            },
        ],
    ),
    ProviderPreset(
        provider_id="gemini_openai_compat",
        label="Gemini OpenAI 兼容",
        base_url="https://generativelanguage.googleapis.com/v1beta/openai/",
        notes="适合看图、多模态与长上下文，但要注意与国产代理的网络条件。",
        models=[
            {
                "id": "gemini-2.5-flash",
                "label": "Gemini 2.5 Flash",
                "supports_vision": True,
                "supports_audio": True,
                "recommended": True,
                "context_note": "适合作为背景旁白器、看图角色和轻量主控。",
            },
            {
                "id": "gemini-2.5-pro",
                "label": "Gemini 2.5 Pro",
                "supports_vision": True,
                "supports_audio": True,
                "recommended": False,
                "context_note": "适合高质量设定抽取与复杂剧情推理。",
            },
        ],
    ),
    ProviderPreset(
        provider_id="ollama_local",
        label="Ollama（本地开源模型）",
        base_url="http://localhost:11434",
        notes="本地部署的开源模型，无需 API Key。支持 Llama、Qwen、Mistral 等。确保本机已启动 ollama serve。",
        models=[
            {
                "id": "qwen2.5:7b",
                "label": "Qwen 2.5 7B",
                "supports_vision": False,
                "supports_audio": False,
                "recommended": True,
                "context_note": "轻量本地模型，适合作为 NPC 角色和日常对话。",
            },
            {
                "id": "qwen2.5:14b",
                "label": "Qwen 2.5 14B",
                "supports_vision": False,
                "supports_audio": False,
                "recommended": True,
                "context_note": "中等规模本地模型，适合主导角色和旁白。",
            },
            {
                "id": "llama3.1:8b",
                "label": "Llama 3.1 8B",
                "supports_vision": False,
                "supports_audio": False,
                "recommended": False,
                "context_note": "英文能力较强，中文表现一般。",
            },
        ],
    ),
]
