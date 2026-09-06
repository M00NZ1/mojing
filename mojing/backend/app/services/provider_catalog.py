from __future__ import annotations

from ..schemas import ProviderPreset

# Only entry-point presets; removing a preset never removes a saved user route.
PROVIDER_CATALOG: list[ProviderPreset] = [
    ProviderPreset(provider_id="deepseek_official", label="DeepSeek", base_url="https://api.deepseek.com", notes="填写自己的 API Key 与模型名称。", models=[]),
    ProviderPreset(provider_id="openai", label="OpenAI", base_url="https://api.openai.com/v1", notes="填写自己的 API Key 与模型名称。", models=[]),
    ProviderPreset(provider_id="siliconflow", label="硅基流动", base_url="https://api.siliconflow.cn/v1", notes="使用平台返回的完整模型 ID。", models=[]),
    ProviderPreset(provider_id="anthropic", label="Anthropic", base_url="https://api.anthropic.com", notes="使用 Anthropic API Key 与模型名称。", models=[]),
    ProviderPreset(provider_id="custom", label="自定义", base_url="", notes="手动填写服务地址、Key 与模型名称。", models=[]),
]
