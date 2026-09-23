from __future__ import annotations

from datetime import datetime

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator


class VoiceProfileRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    provider: str
    reference_audio_path: str
    language: str
    description: str
    created_at: datetime


class CharacterBase(BaseModel):
    name: str
    persona_prompt: str = ""
    api_key: str = ""
    api_base_url: str = "https://api.deepseek.com"
    model_name: str = "deepseek-chat"
    temperature: float = 0.9
    max_tokens: int = 1200
    top_p: float = 1.0
    top_k: int = 0
    frequency_penalty: float = 0.0
    presence_penalty: float = 0.0
    repetition_penalty: float = 1.0
    avatar_color: str = "#F97316"
    avatar_image_path: str = ""
    card_image_path: str = ""
    voice_profile_id: int | None = None
    voice_provider: str = ""
    voice_api_base_url: str = ""
    voice_api_key: str = ""
    voice_model: str = ""
    image_gen_enabled: bool = False
    image_gen_api_key: str = ""
    image_gen_base_url: str = ""
    image_gen_model: str = "dall-e-3"
    think_max_enabled: bool = False
    think_max_model_name: str = ""


class CharacterCreate(CharacterBase):
    pass


class CharacterUpdate(CharacterBase):
    clear_api_key: bool = False
    clear_voice_api_key: bool = False
    clear_image_gen_api_key: bool = False


class CharacterRead(CharacterBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime
    voice_profile: VoiceProfileRead | None = None
    favorite: bool = False


class CharacterCardImageGenBody(BaseModel):
    """角色卡竖图：可选补充提示；尺寸随上游支持（如 1024x1792）。"""

    prompt_hint: str = ""
    size: str = Field(default="1024x1792", max_length=40)


class CharacterCardImagePreviewBody(BaseModel):
    """不落库预览：用于仅配置了公共生图、或本地 Room 未同步服务端人物 id 的客户端。"""

    name: str = ""
    persona_prompt: str = ""
    prompt_hint: str = ""
    size: str = Field(default="1024x1792", max_length=40)


class CharacterCardImagePreviewResult(BaseModel):
    urls: list[str] = []
    revised_prompt: str = ""
    error: str | None = None


class EncyclopediaEntryCoverPreviewBody(BaseModel):
    """百科条目封面不落库预览：本地 Room 条目 id 与服务器不一致时使用。"""

    title: str = ""
    entry_type: str = "concept"
    summary: str = ""
    prompt_hint: str = ""
    size: str = Field(default="1024x1792", max_length=40)


class EncyclopediaPersistCoverFromUrlBody(BaseModel):
    """将预览 URL 落盘到 STORAGE（供 Web 新建条目等无 entry id 场景）。"""

    image_url: str = Field(..., min_length=8, max_length=4000)


class CharacterTemplate(BaseModel):
    id: str
    label: str
    gender: str
    age_group: str
    summary: str
    persona_prompt: str
    recommended_voice_names: list[str] = []


class CharacterProfileRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    character_id: int
    source_filename: str
    raw_persona_text: str
    character_card_json: dict
    character_card_markdown: str
    extracted_at: datetime


class CharacterProfileImportRequest(BaseModel):
    source_text: str
    source_filename: str = "persona.txt"
    merge_into_persona_prompt: bool = True


class SessionWorldRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    session_id: int
    encyclopedia_id: int | None = None
    world_prompt: str
    template_id: str
    gameplay_mode: str
    narrator_enabled: bool
    narrator_name: str
    choice_generation_enabled: bool
    max_choice_count: int
    suggested_choices_json: list[str] = []
    anti_cheat_enabled: bool
    anti_cheat_prompt: str
    world_timeline_json: list = []
    auto_sediment_enabled: bool = True
    sediment_interval: int = 20
    updated_at: datetime


class SessionRead(BaseModel):
    id: int
    title: str
    summary: str
    created_at: datetime
    updated_at: datetime
    message_count: int = 0
    participant_count: int = 0
    think_max_enabled: bool = False
    last_message_preview: str | None = Field(default=None, description="各会话全局最新一条消息摘要（跨分支）")
    world: SessionWorldRead | None = None


class SessionWorldUpdate(BaseModel):
    encyclopedia_id: int | None = None
    world_prompt: str = ""
    template_id: str = "custom"
    gameplay_mode: str = "自由剧情"
    narrator_enabled: bool = False
    narrator_name: str = "旁白"
    choice_generation_enabled: bool = True
    max_choice_count: int = 3
    suggested_choices_json: list[str] = []
    anti_cheat_enabled: bool = True
    anti_cheat_prompt: str = ""
    auto_sediment_enabled: bool = True
    sediment_interval: int = 20


class SessionCreate(BaseModel):
    title: str = "新对话"
    template_id: str = "custom"
    encyclopedia_id: int | None = None
    gameplay_mode: str = "自由剧情"
    narrator_enabled: bool = False
    narrator_name: str = "旁白"
    choice_generation_enabled: bool = True
    max_choice_count: int = 3
    anti_cheat_enabled: bool = True
    initial_character_ids: list[int] | None = Field(
        default=None,
        description="创建后写入 session_participants；顺序保留，自动去重。不传或 null 表示不预绑（兼容旧客户端）。",
    )


class SessionUpdate(BaseModel):
    title: str | None = None
    think_max_enabled: bool | None = None


class ParticipantRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    sort_order: int
    talkativeness: float = 0.7
    character: CharacterRead


class SessionParticipantCreate(BaseModel):
    character_id: int


class SessionParticipantUpdate(BaseModel):
    talkativeness: float | None = Field(default=None, ge=0.05, le=1.0)


class SessionMessageCreate(BaseModel):
    content: str = Field(min_length=1)
    branch_id: str = "main"


class SessionMessageEdit(SessionMessageCreate):
    pass


class AttachmentRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    asset_type: str
    file_name: str
    mime_type: str
    storage_path: str
    created_at: datetime


class MessageRead(BaseModel):
    include_in_context: bool = True
    id: int
    session_id: int
    speaker_type: str
    character_id: int | None = None
    branch_id: str = "main"
    parent_message_id: int | None = None
    regenerated_from_message_id: int | None = None
    swipe_group_id: str | None = None
    character_name: str | None = None
    character_avatar_path: str | None = None
    content: str
    structured_content: dict
    created_at: datetime
    attachments: list[AttachmentRead] = []


class MessagePage(BaseModel):
    items: list[MessageRead]
    next_cursor: int | None = None


class MessageContextUpdate(BaseModel):
    include_in_context: StrictBool
    expected_include_in_context: StrictBool | None = None
    branch_id: str | None = None


class MessageWindowPage(BaseModel):
    items: list[MessageRead]
    older_cursor: int | None = None
    newer_cursor: int | None = None


class MessageSearchHitRead(BaseModel):
    id: int
    session_id: int
    speaker_type: str
    character_id: int | None = None
    character_name: str | None = None
    branch_id: str
    snippet: str
    created_at: datetime


class SessionBranchRead(BaseModel):
    branch_id: str
    label: str = ""
    source_message_id: int | None = None
    parent_branch_id: str | None = None
    source_message_preview: str | None = None
    source_created_at: datetime | None = None
    latest_created_at: datetime | None = None
    depth: int = 0
    message_count: int = 0
    latest_message_id: int | None = None


class SessionBranchCreate(BaseModel):
    source_message_id: int
    branch_id: str = ""
    label: str = ""
    parent_branch_id: str = "main"


class GenerateRequest(BaseModel):
    user_message: str | None = None
    character_ids: list[int] = []
    include_narrator: bool = False
    narrator_only: bool = False
    auto_select_speakers: bool = False
    max_auto_speakers: int = 2
    branch_id: str = "main"


class VoiceBindingRequest(BaseModel):
    voice_profile_id: int | None = None


class VoiceSynthesisClip(BaseModel):
    kind: str
    text: str
    url: str


class VoiceSynthesisResponse(BaseModel):
    message_id: int
    clips: list[VoiceSynthesisClip]


class PromptRewriteRequest(BaseModel):
    character_id: int
    source_text: str
    instruction: str
    chunk_size: int = 2800


class PromptRewriteResponse(BaseModel):
    result: str


class SessionCharacterStateRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    session_id: int
    character_id: int
    dynamic_state_json: dict
    relations_json: dict
    private_facts_json: list
    event_log_json: list
    last_compacted_message_id: int | None = None
    goals_json: list = []
    emotional_state: str = ""
    last_significant_event_id: int | None = None
    updated_at: datetime


class MemorySegmentRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    session_id: int
    branch_id: str
    segment_index: int
    start_message_id: int
    end_message_id: int
    summary: str
    key_facts: list = []
    key_characters: list = []
    emotional_tone: str
    created_at: datetime


class MessageSearchIndexRead(BaseModel):
    ready: bool
    indexed_count: int


class MessageSearchPageRead(BaseModel):
    items: list[MessageSearchHitRead]
    next_cursor: int | None = None
    total_count: int | None = None
    index: MessageSearchIndexRead


class SessionMemoryCorrectionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    session_id: int
    branch_id: str | None = None
    content: str
    source_message_id: int | None = None
    created_at: datetime
    updated_at: datetime


class SessionMemoryCorrectionWrite(BaseModel):
    content: str = Field(min_length=1, max_length=2000)
    branch_id: str | None = None
    source_message_id: int | None = None

    @field_validator("content")
    @classmethod
    def content_must_be_non_empty(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("content 不能为空")
        return value


class EventNodeRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    session_id: int
    character_id: int | None = None
    branch_id: str
    parent_event_id: int | None = None
    event_type: str
    title: str
    description: str
    importance: int
    message_id: int | None = None
    resolved: bool
    created_at: datetime


class AiCompleteRequest(BaseModel):
    target_type: str
    target_data: dict = {}
    fields_to_complete: list[str] | None = None
    character_id: int | None = None
    extra_context: str = ""


class AiCompleteResponse(BaseModel):
    completed_fields: dict = {}
    model_used: str = ""
    token_usage: dict = {}


class SpeakerPlanRead(BaseModel):
    character_ids: list[int]
    reason: str


class WorldTemplateRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    encyclopedia_id: int | None = None
    template_id: str
    label: str
    category: str
    summary: str
    gameplay_mode: str
    world_prompt: str
    cover_image_path: str = ""
    suggested_choices: list[str] = []
    anti_cheat_prompt: str = ""
    is_builtin: bool = False


class WorldTemplateCreate(BaseModel):
    template_id: str
    label: str
    category: str
    summary: str
    gameplay_mode: str
    world_prompt: str
    cover_image_path: str = ""
    suggested_choices: list[str] = []
    anti_cheat_prompt: str = ""


class WorldTemplateUpdate(BaseModel):
    label: str
    category: str
    summary: str
    gameplay_mode: str
    world_prompt: str
    cover_image_path: str = ""
    suggested_choices: list[str] = []
    anti_cheat_prompt: str = ""


class WorldLoreEntryRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    world_template_id: int
    title: str
    entry_type: str
    keywords_json: list[str] = []
    content: str
    sort_order: int
    is_core: bool
    created_at: datetime
    updated_at: datetime


class WorldLoreEntryCreate(BaseModel):
    title: str
    entry_type: str = "设定"
    keywords_json: list[str] = []
    content: str
    sort_order: int = 0
    is_core: bool = False


class WorldLoreEntryUpdate(WorldLoreEntryCreate):
    pass


class PromptTemplateRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    template_id: str
    label: str
    category: str
    scope: str
    description: str
    system_prompt: str
    user_prompt: str
    variables_json: list[str] = []
    output_format: str
    version: int
    is_builtin: bool
    created_at: datetime
    updated_at: datetime


class PromptTemplateRevisionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    prompt_template_id: int
    version: int
    system_prompt: str
    user_prompt: str
    variables_json: list[str] = []
    output_format: str
    change_note: str
    created_at: datetime


class PromptTemplateCreate(BaseModel):
    template_id: str
    label: str
    category: str = "通用"
    scope: str = "story"
    description: str = ""
    system_prompt: str = ""
    user_prompt: str = ""
    variables_json: list[str] = []
    output_format: str = ""
    change_note: str = "新建模板"


class PromptTemplateUpdate(BaseModel):
    label: str
    category: str = "通用"
    scope: str = "story"
    description: str = ""
    system_prompt: str = ""
    user_prompt: str = ""
    variables_json: list[str] = []
    output_format: str = ""
    change_note: str = "更新模板"


class WorldGenerationRequest(BaseModel):
    character_id: int | None = None
    world_type: str
    core_theme: str = ""
    tone: str = "偏严谨、可长期推进"
    extra_requirements: str = ""
    person_name_count: int = 8
    place_name_count: int = 8
    item_name_count: int = 8
    lore_entry_count: int = 6
    auto_save: bool = False
    template_id: str = ""
    label: str = ""


class GeneratedNamePack(BaseModel):
    person_names: list[str] = []
    place_names: list[str] = []
    item_names: list[str] = []


class WorldImportRequest(BaseModel):
    character_id: int | None = None
    source_text: str
    source_filename: str = "world.txt"
    category_hint: str = ""
    template_id: str = ""
    label: str = ""
    auto_save: bool = False


class WorldQualityIssueRead(BaseModel):
    level: str
    code: str
    message: str
    suggestion: str = ""


class WorldQualityReportRead(BaseModel):
    score: int
    verdict: str
    strengths: list[str] = []
    risks: list[str] = []
    issues: list[WorldQualityIssueRead] = []


class WorldQualityPreviewRequest(BaseModel):
    """对当前表单 + Lore 列表运行 review_world_package（无需先保存）。"""

    template_id: str = "preview"
    label: str = ""
    category: str = ""
    summary: str = ""
    gameplay_mode: str = ""
    world_prompt: str = ""
    cover_image_path: str = ""
    suggested_choices: list[str] = []
    anti_cheat_prompt: str = ""
    lore_entries: list[WorldLoreEntryCreate] = []


class WorldImportChunkDebugRead(BaseModel):
    chunk_index: int
    char_length: int
    summary: str = ""
    extracted_titles: list[str] = []
    extracted_types: list[str] = []
    extracted_names: GeneratedNamePack = GeneratedNamePack()
    strategy: str = ""


class WorldBuildDebugRead(BaseModel):
    strategy: str
    detected_category: str
    chunk_count: int = 1
    merge_notes: list[str] = []
    chunk_debug: list[WorldImportChunkDebugRead] = []


class WorldGenerationResponse(BaseModel):
    job_id: int | None = None
    template: WorldTemplateCreate
    lore_entries: list[WorldLoreEntryCreate] = []
    names: GeneratedNamePack = GeneratedNamePack()
    quality_report: WorldQualityReportRead
    debug: WorldBuildDebugRead | None = None
    saved_template: WorldTemplateRead | None = None


class WorldImportResponse(BaseModel):
    job_id: int | None = None
    template: WorldTemplateCreate
    lore_entries: list[WorldLoreEntryCreate] = []
    names: GeneratedNamePack = GeneratedNamePack()
    quality_report: WorldQualityReportRead
    debug: WorldBuildDebugRead | None = None
    saved_template: WorldTemplateRead | None = None


class WorldTemplatePackageRead(BaseModel):
    format_version: int = 1
    exported_at: datetime
    template: WorldTemplateCreate
    lore_entries: list[WorldLoreEntryCreate] = []


class WorldTemplatePackageImportRequest(BaseModel):
    package_json: dict
    override_existing: bool = False
    new_template_id: str = ""
    new_label: str = ""


class WorldTemplateBundleRead(BaseModel):
    format_version: int = 1
    exported_at: datetime
    package_count: int
    packages: list[WorldTemplatePackageRead] = []


class WorldTemplateBundleImportRequest(BaseModel):
    bundle_json: dict
    override_existing: bool = False
    replace_all_custom_templates: bool = False


class WorldTemplateBundlePreviewItemRead(BaseModel):
    template_id: str
    label: str
    category: str
    lore_entry_count: int = 0
    action: str
    conflict_reason: str = ""
    existing_label: str = ""
    existing_is_builtin: bool = False


class WorldTemplateBundlePreviewRead(BaseModel):
    package_count: int
    create_count: int = 0
    overwrite_count: int = 0
    recreate_count: int = 0
    blocked_count: int = 0
    duplicate_ids: list[str] = []
    replace_all_custom_templates: bool = False
    will_delete_template_ids: list[str] = []
    warnings: list[str] = []
    items: list[WorldTemplateBundlePreviewItemRead] = []


class EncyclopediaTemplateBootstrapRead(BaseModel):
    encyclopedia_id: int
    created_count: int = 0
    updated_count: int = 0
    skipped_count: int = 0


class EncyclopediaSourceItem(BaseModel):
    title: str = ""
    url: str = ""
    api_url: str = ""
    source_text: str = ""
    source_license: str = ""
    entry_type: str = "concept"
    tags: list[str] = []
    trust_level: str = "wiki"
    canon_scope: str = ""
    canon_conflicts: list[str] = []
    unknown_fields: list[str] = []
    is_featured: bool = False
    verified: bool = False
    sort_order: int = 0


class EncyclopediaSourceImportRequest(BaseModel):
    sources: list[EncyclopediaSourceItem] = []
    dry_run: bool = True
    overwrite_existing: bool = False
    max_extract_chars: int = 6000


class EncyclopediaSourceImportItemRead(BaseModel):
    title: str
    entry_type: str = "concept"
    status: str
    entry_id: int | None = None
    source_url: str = ""
    warnings: list[str] = []


class EncyclopediaSourceImportResponse(BaseModel):
    encyclopedia_id: int
    dry_run: bool
    imported_count: int = 0
    skipped_count: int = 0
    failed_count: int = 0
    items: list[EncyclopediaSourceImportItemRead] = []


class AssetItemRead(BaseModel):
    id: str
    label: str
    category: str
    kind: str
    preview_path: str = ""
    storage_path: str = ""
    external_url: str = ""
    source_label: str = ""
    author: str = ""
    license_name: str = ""
    attribution_required: bool = False
    is_builtin: bool = False
    tags: list[str] = []


class ProviderModelPreset(BaseModel):
    id: str
    label: str
    supports_vision: bool = False
    supports_audio: bool = False
    recommended: bool = False
    context_note: str = ""


class ProviderPreset(BaseModel):
    provider_id: str
    label: str
    base_url: str
    model_param_name: str = ""
    notes: str = ""
    models: list[ProviderModelPreset] = []


class SystemStatusRead(BaseModel):
    database_ready: bool = True
    builtin_tts_ready: bool = False
    cloning_tts_ready: bool = False
    builtin_voice_count: int = 0
    python_version: str = ""
    cloning_status_message: str = ""
    external_voice_enabled: bool = False
    recommended_flow: list[str] = []


class VoiceServiceConfigRead(BaseModel):
    enabled: bool
    mode: str = "builtin_only"
    external_base_url: str = ""
    external_api_key: str = ""
    clone_endpoint: str = "/clone"
    timeout_seconds: int = 120


class LocalConfigRead(BaseModel):
    default_world_template_id: str = "custom"
    default_narrator_enabled: bool = False
    default_choice_generation_enabled: bool = True
    default_anti_cheat_enabled: bool = True
    memory_compact_threshold: int = 12
    max_upload_mb: int = 20
    max_auto_speakers: int = 4
    public_text_api_key: str = ""
    public_text_base_url: str = ""
    public_text_model: str = ""
    public_image_api_key: str = ""
    public_image_base_url: str = ""
    public_image_model: str = "dall-e-3"
    public_voice_api_key: str = ""
    public_voice_base_url: str = ""
    public_voice_model: str = ""
    allow_session_think_max: bool = False
    think_max_model: str = "deepseek-reasoner"


class TimelineRead(BaseModel):
    entries: list = []
    session_id: int
    gameplay_mode: str


class JobRunRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    job_type: str
    status: str
    scope: str
    target_id: int | None = None
    input_json: dict = {}
    output_json: dict = {}
    error_message: str = ""
    created_at: datetime
    updated_at: datetime
    started_at: datetime | None = None
    finished_at: datetime | None = None


class TimelineEntry(BaseModel):
    id: int | None = None
    session_id: int | None = None
    title: str
    description: str = ""
    entry_type: str = "general"
    event_type: str = ""
    event_data: dict = {}
    character_id: int | None = None
    turn_number: int | None = None
    timestamp: datetime | None = None
    sort_order: int = 0
    created_at: datetime | None = None


class StoryWritingRequest(BaseModel):
    premise: str = Field(..., min_length=1, max_length=12000)
    direction: str = Field(default="", max_length=2000)
    tone: str = Field(default="", max_length=500)
    chapter_count: int = Field(default=2, ge=1, le=3)
    template_id: str | None = None
    encyclopedia_id: int | None = None
    character_ids: list[int] = Field(default_factory=list)
    request_id: str | None = Field(default=None, max_length=128)

    @field_validator("request_id")
    @classmethod
    def request_id_must_be_safe(cls, value: str | None) -> str | None:
        if value is not None and not re.fullmatch(r"[A-Za-z0-9._:-]+", value):
            raise ValueError("request_id 格式不正确")
        return value


class StoryWritingChapter(BaseModel):
    number: int = Field(..., ge=1)
    title: str = Field(..., min_length=1)
    content: str = Field(..., min_length=1)


class StoryWritingResult(BaseModel):
    mode: Literal["story_writing"] = "story_writing"
    session_id: int
    title: str
    chapter_count: int
    status: Literal["created"] = "created"


class StoryGeneratedRecoveryRequest(BaseModel):
    version: Literal[1]
    payload: StoryWritingRequest
    text: str
    draft_json: dict
    context_text: str


class StoryGenerationRequestStatus(BaseModel):
    status: Literal["missing", "draft", "saved"]
    request_id: str
    title: str | None = None
    text: str | None = None
    chapter_count: int | None = None
    session_id: int | None = None


class LocalConfigUpdate(BaseModel):
    default_world_template_id: str | None = None
    default_narrator_enabled: bool | None = None
    default_choice_generation_enabled: bool | None = None
    default_anti_cheat_enabled: bool | None = None
    memory_compact_threshold: int | None = Field(default=None, ge=10, le=40)
    max_upload_mb: int | None = None
    max_auto_speakers: int | None = None
    public_text_api_key: str | None = None
    public_text_base_url: str | None = None
    public_text_model: str | None = None
    public_image_api_key: str | None = None
    public_image_base_url: str | None = None
    public_image_model: str | None = None
    public_voice_api_key: str | None = None
    public_voice_base_url: str | None = None
    public_voice_model: str | None = None
    allow_session_think_max: bool | None = None
    think_max_model: str | None = None
    clear_public_text_api_key: bool = False
    clear_public_image_api_key: bool = False
    clear_public_voice_api_key: bool = False


class ProbePublicApiRequest(BaseModel):
    channel: Literal["text", "image", "voice"]
    """为 None 时从已保存的 local_config 读取对应字段。"""
    base_url: str | None = None
    api_key: str | None = None
    model: str | None = None
    character_id: int | None = Field(default=None, ge=1)


class PublicApiProbeAttempt(BaseModel):
    base_url: str = ""
    ok: bool = False
    error: str | None = None
    detail: str | None = None


class PublicApiProbeResult(BaseModel):
    ok: bool
    channel: str
    error: str | None = None
    detail: str | None = None
    warnings: list[str] = Field(default_factory=list)
    meta: dict | None = None
    used_base_url: str | None = None
    attempts: list[PublicApiProbeAttempt] = Field(default_factory=list)


class VoiceServiceConfigUpdate(BaseModel):
    mode: str | None = None
    external_base_url: str | None = None
    external_api_key: str | None = None
    clone_endpoint: str | None = None
    timeout_seconds: int | None = None
    enabled: bool | None = None
    clear_external_api_key: bool = False
