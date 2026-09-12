from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import DateTime, Float, ForeignKey, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


def now_utc() -> datetime:
    """统一时间戳工厂。"""

    return datetime.now(timezone.utc)


class ChatSessionModel(Base):
    """对话会话。"""

    __tablename__ = "chat_sessions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    title: Mapped[str] = mapped_column(String(200), default="新对话")
    summary: Mapped[str] = mapped_column(Text, default="")
    think_max_enabled: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    messages: Mapped[list["MessageModel"]] = relationship(back_populates="session", cascade="all, delete-orphan", order_by="MessageModel.id")
    participants: Mapped[list["SessionParticipantModel"]] = relationship(back_populates="session", cascade="all, delete-orphan", order_by="SessionParticipantModel.sort_order")
    world: Mapped["SessionWorldModel | None"] = relationship(back_populates="session", cascade="all, delete-orphan", uselist=False)
    character_states: Mapped[list["SessionCharacterStateModel"]] = relationship(back_populates="session", cascade="all, delete-orphan")
    branches: Mapped[list["SessionBranchModel"]] = relationship(back_populates="session", cascade="all, delete-orphan", order_by="SessionBranchModel.created_at")
    memory_corrections: Mapped[list["SessionMemoryCorrectionModel"]] = relationship(back_populates="session", cascade="all, delete-orphan")


class StoryRequestReceiptModel(Base):
    """故事生成完成回执；不绑定外键，以便已删除会话可返回 410。"""

    __tablename__ = "story_request_receipts"

    request_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    receipt_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    payload_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    session_id: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    session_created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    result_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class StoryGenerationDraftModel(Base):
    """完整故事正文暂存；仅用于带 request_id 的可恢复请求。"""

    __tablename__ = "story_generation_drafts"

    request_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    draft_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    payload_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    payload_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    context_text: Mapped[str] = mapped_column(Text, nullable=False, default="")
    draft_json: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class LlmCostRecordModel(Base):
    """LLM 调用成本记录。"""

    __tablename__ = "llm_cost_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[int] = mapped_column(Integer, index=True, nullable=True)
    character_id: Mapped[int] = mapped_column(Integer, nullable=True)
    model_name: Mapped[str] = mapped_column(String(120), default="")
    provider: Mapped[str] = mapped_column(String(60), default="openai")
    prompt_tokens: Mapped[int] = mapped_column(Integer, default=0)
    completion_tokens: Mapped[int] = mapped_column(Integer, default=0)
    total_tokens: Mapped[int] = mapped_column(Integer, default=0)
    estimated_cost: Mapped[float] = mapped_column(Float, default=0.0)
    duration_ms: Mapped[int] = mapped_column(Integer, default=0)
    success: Mapped[bool] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class VoiceProfileModel(Base):
    """上传语音后生成的声色档案。"""

    __tablename__ = "voice_profiles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(120), unique=True)
    provider: Mapped[str] = mapped_column(String(50), default="xtts_v2")
    reference_audio_path: Mapped[str] = mapped_column(String(500))
    language: Mapped[str] = mapped_column(String(32), default="zh-cn")
    description: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)

    characters: Mapped[list["CharacterModel"]] = relationship(back_populates="voice_profile")


class WorldTemplateModel(Base):
    """全局世界模板库，支持内置模板和用户自定义模板。"""

    __tablename__ = "world_templates"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    template_id: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    label: Mapped[str] = mapped_column(String(120), default="")
    category: Mapped[str] = mapped_column(String(80), default="通用")
    summary: Mapped[str] = mapped_column(Text, default="")
    gameplay_mode: Mapped[str] = mapped_column(String(80), default="自由剧情")
    world_prompt: Mapped[str] = mapped_column(Text, default="")
    cover_image_path: Mapped[str] = mapped_column(String(500), default="")
    suggested_choices_json: Mapped[list] = mapped_column(JSON, default=list)
    anti_cheat_prompt: Mapped[str] = mapped_column(Text, default="")
    is_builtin: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    lore_entries: Mapped[list["WorldLoreEntryModel"]] = relationship(
        back_populates="world_template",
        cascade="all, delete-orphan",
        order_by="WorldLoreEntryModel.sort_order",
    )


class WorldLoreEntryModel(Base):
    """世界模板下的 Lorebook 条目。"""

    __tablename__ = "world_lore_entries"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    world_template_id: Mapped[int] = mapped_column(ForeignKey("world_templates.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(160), default="")
    entry_type: Mapped[str] = mapped_column(String(60), default="设定")
    keywords_json: Mapped[list] = mapped_column(JSON, default=list)
    content: Mapped[str] = mapped_column(Text, default="")
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    is_core: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    world_template: Mapped[WorldTemplateModel] = relationship(back_populates="lore_entries")


class PromptTemplateModel(Base):
    """全局提示词模板库。"""

    __tablename__ = "prompt_templates"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    template_id: Mapped[str] = mapped_column(String(100), unique=True, index=True)
    label: Mapped[str] = mapped_column(String(140), default="")
    category: Mapped[str] = mapped_column(String(80), default="通用")
    scope: Mapped[str] = mapped_column(String(80), default="story")
    description: Mapped[str] = mapped_column(Text, default="")
    system_prompt: Mapped[str] = mapped_column(Text, default="")
    user_prompt: Mapped[str] = mapped_column(Text, default="")
    variables_json: Mapped[list] = mapped_column(JSON, default=list)
    output_format: Mapped[str] = mapped_column(Text, default="")
    version: Mapped[int] = mapped_column(Integer, default=1)
    is_builtin: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    revisions: Mapped[list["PromptTemplateRevisionModel"]] = relationship(
        back_populates="prompt_template",
        cascade="all, delete-orphan",
        order_by="PromptTemplateRevisionModel.version.desc()",
    )


class PromptTemplateRevisionModel(Base):
    """提示词模板修订历史。"""

    __tablename__ = "prompt_template_revisions"
    __table_args__ = (UniqueConstraint("prompt_template_id", "version", name="uq_prompt_template_version"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    prompt_template_id: Mapped[int] = mapped_column(ForeignKey("prompt_templates.id", ondelete="CASCADE"), index=True)
    version: Mapped[int] = mapped_column(Integer, default=1)
    system_prompt: Mapped[str] = mapped_column(Text, default="")
    user_prompt: Mapped[str] = mapped_column(Text, default="")
    variables_json: Mapped[list] = mapped_column(JSON, default=list)
    output_format: Mapped[str] = mapped_column(Text, default="")
    change_note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)

    prompt_template: Mapped[PromptTemplateModel] = relationship(back_populates="revisions")


class CharacterModel(Base):
    """人物配置，支持独立人设与独立 API。"""

    __tablename__ = "characters"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(120), unique=True)
    persona_prompt: Mapped[str] = mapped_column(Text, default="")
    api_key: Mapped[str] = mapped_column(Text, default="")
    api_base_url: Mapped[str] = mapped_column(String(255), default="https://api.deepseek.com")
    model_name: Mapped[str] = mapped_column(String(120), default="deepseek-chat")
    temperature: Mapped[float] = mapped_column(Float, default=0.9)
    max_tokens: Mapped[int] = mapped_column(Integer, default=1200)
    top_p: Mapped[float] = mapped_column(Float, default=1.0)
    top_k: Mapped[int] = mapped_column(Integer, default=0)
    frequency_penalty: Mapped[float] = mapped_column(Float, default=0.0)
    presence_penalty: Mapped[float] = mapped_column(Float, default=0.0)
    repetition_penalty: Mapped[float] = mapped_column(Float, default=1.0)
    avatar_color: Mapped[str] = mapped_column(String(20), default="#F97316")
    avatar_image_path: Mapped[str] = mapped_column(String(500), default="")
    card_image_path: Mapped[str] = mapped_column(String(500), default="")
    voice_profile_id: Mapped[int | None] = mapped_column(ForeignKey("voice_profiles.id"), nullable=True)
    voice_provider: Mapped[str] = mapped_column(String(50), default="")
    voice_api_base_url: Mapped[str] = mapped_column(String(500), default="")
    voice_api_key: Mapped[str] = mapped_column(Text, default="")
    voice_model: Mapped[str] = mapped_column(String(200), default="")
    image_gen_enabled: Mapped[bool] = mapped_column(default=False)
    image_gen_api_key: Mapped[str] = mapped_column(Text, default="")
    image_gen_base_url: Mapped[str] = mapped_column(String(500), default="")
    image_gen_model: Mapped[str] = mapped_column(String(120), default="dall-e-3")
    think_max_enabled: Mapped[bool] = mapped_column(default=False)
    think_max_model_name: Mapped[str] = mapped_column(String(120), default="")
    favorite: Mapped[bool] = mapped_column(Integer, default=0)  # 收藏置顶
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    voice_profile: Mapped[VoiceProfileModel | None] = relationship(back_populates="characters")
    session_links: Mapped[list["SessionParticipantModel"]] = relationship(
        back_populates="character",
        cascade="all, delete-orphan",
    )
    messages: Mapped[list["MessageModel"]] = relationship(back_populates="character")
    session_states: Mapped[list["SessionCharacterStateModel"]] = relationship(
        back_populates="character",
        cascade="all, delete-orphan",
    )
    profile: Mapped["CharacterProfileModel | None"] = relationship(
        back_populates="character",
        cascade="all, delete-orphan",
        uselist=False,
    )
    expressions: Mapped[list["CharacterExpressionModel"]] = relationship(
        back_populates="character",
        cascade="all, delete-orphan",
        order_by="CharacterExpressionModel.sort_order",
    )


class CharacterExpressionModel(Base):
    """角色表情/立绘多图管理。"""

    __tablename__ = "character_expressions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    character_id: Mapped[int] = mapped_column(ForeignKey("characters.id", ondelete="CASCADE"))
    expression: Mapped[str] = mapped_column(String(60), default="default")
    label: Mapped[str] = mapped_column(String(120), default="")
    image_path: Mapped[str] = mapped_column(String(500), default="")
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)

    character: Mapped["CharacterModel"] = relationship(back_populates="expressions")


class SessionParticipantModel(Base):
    """会话与人物的绑定关系。"""

    __tablename__ = "session_participants"
    __table_args__ = (UniqueConstraint("session_id", "character_id", name="uq_session_character"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"))
    character_id: Mapped[int] = mapped_column(ForeignKey("characters.id", ondelete="CASCADE"))
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    talkativeness: Mapped[float] = mapped_column(Float, default=0.7)
    muted: Mapped[bool] = mapped_column(Integer, default=0)
    force_next: Mapped[bool] = mapped_column(Integer, default=0)
    allow_self_response: Mapped[bool] = mapped_column(Integer, default=0)
    speaker_strategy: Mapped[str] = mapped_column(String(30), default="natural")

    session: Mapped[ChatSessionModel] = relationship(back_populates="participants")
    character: Mapped[CharacterModel] = relationship(back_populates="session_links")


class MessageModel(Base):
    """消息实体。"""

    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"), index=True)
    speaker_type: Mapped[str] = mapped_column(String(30), default="user")
    character_id: Mapped[int | None] = mapped_column(ForeignKey("characters.id"), nullable=True)
    branch_id: Mapped[str] = mapped_column(String(80), default="main", index=True)
    parent_message_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    regenerated_from_message_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    swipe_group_id: Mapped[str | None] = mapped_column(String(80), nullable=True, index=True)
    content: Mapped[str] = mapped_column(Text, default="")
    structured_content: Mapped[dict] = mapped_column(JSON, default=dict)
    include_in_context: Mapped[bool] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, index=True)

    session: Mapped[ChatSessionModel] = relationship(back_populates="messages")
    character: Mapped[CharacterModel | None] = relationship(back_populates="messages")
    attachments: Mapped[list["MessageAttachmentModel"]] = relationship(
        back_populates="message",
        cascade="all, delete-orphan",
        order_by="MessageAttachmentModel.id",
    )


class SessionBranchModel(Base):
    """会话分支元数据。"""

    __tablename__ = "session_branches"
    __table_args__ = (UniqueConstraint("session_id", "branch_id", name="uq_session_branch"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"), index=True)
    branch_id: Mapped[str] = mapped_column(String(80), index=True)
    label: Mapped[str] = mapped_column(String(120), default="")
    source_message_id: Mapped[int] = mapped_column(Integer, index=True)
    parent_branch_id: Mapped[str] = mapped_column(String(80), default="main")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)

    session: Mapped[ChatSessionModel] = relationship(back_populates="branches")

    is_checkpoint: Mapped[bool] = mapped_column(Integer, default=0)
    checkpoint_label: Mapped[str] = mapped_column(String(200), default="")


class SessionWorldModel(Base):
    """会话级背景与旁白器配置。"""

    __tablename__ = "session_worlds"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"), unique=True)
    encyclopedia_id: Mapped[int | None] = mapped_column(ForeignKey("world_encyclopedias.id"), nullable=True, index=True)
    world_prompt: Mapped[str] = mapped_column(Text, default="")
    template_id: Mapped[str] = mapped_column(String(80), default="custom")
    gameplay_mode: Mapped[str] = mapped_column(String(80), default="自由剧情")
    narrator_enabled: Mapped[bool] = mapped_column(default=False)
    narrator_name: Mapped[str] = mapped_column(String(80), default="旁白")
    choice_generation_enabled: Mapped[bool] = mapped_column(default=True)
    max_choice_count: Mapped[int] = mapped_column(Integer, default=3)
    suggested_choices_json: Mapped[list] = mapped_column(JSON, default=list)
    anti_cheat_enabled: Mapped[bool] = mapped_column(default=True)
    anti_cheat_prompt: Mapped[str] = mapped_column(Text, default="")
    world_timeline_json: Mapped[list] = mapped_column(JSON, default=list)
    auto_sediment_enabled: Mapped[bool] = mapped_column(default=True)
    sediment_interval: Mapped[int] = mapped_column(Integer, default=20)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    session: Mapped[ChatSessionModel] = relationship(back_populates="world")


class CharacterProfileModel(Base):
    """人物原始设定与抽取后的人物卡。"""

    __tablename__ = "character_profiles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    character_id: Mapped[int] = mapped_column(ForeignKey("characters.id", ondelete="CASCADE"), unique=True)
    source_filename: Mapped[str] = mapped_column(String(255), default="")
    raw_persona_text: Mapped[str] = mapped_column(Text, default="")
    character_card_json: Mapped[dict] = mapped_column(JSON, default=dict)
    character_card_markdown: Mapped[str] = mapped_column(Text, default="")
    extracted_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    character: Mapped[CharacterModel] = relationship(back_populates="profile")


class SessionCharacterStateModel(Base):
    """人物在某个会话中的动态状态与长期记忆。"""

    __tablename__ = "session_character_states"
    __table_args__ = (UniqueConstraint("session_id", "character_id", name="uq_session_character_state"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"))
    character_id: Mapped[int] = mapped_column(ForeignKey("characters.id", ondelete="CASCADE"))
    dynamic_state_json: Mapped[dict] = mapped_column(JSON, default=dict)
    relations_json: Mapped[dict] = mapped_column(JSON, default=dict)
    private_facts_json: Mapped[list] = mapped_column(JSON, default=list)
    event_log_json: Mapped[list] = mapped_column(JSON, default=list)
    last_compacted_message_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    goals_json: Mapped[list] = mapped_column(JSON, default=list)
    emotional_state: Mapped[str] = mapped_column(String(60), default="")
    last_significant_event_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    session: Mapped[ChatSessionModel] = relationship(back_populates="character_states")
    character: Mapped[CharacterModel] = relationship(back_populates="session_states")


class SessionMemorySegmentModel(Base):
    """会话记忆分段。每个分段覆盖一段消息范围的摘要。"""

    __tablename__ = "session_memory_segments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"), index=True)
    branch_id: Mapped[str] = mapped_column(String(80), default="main", index=True)
    segment_index: Mapped[int] = mapped_column(Integer, default=0)
    start_message_id: Mapped[int] = mapped_column(Integer, default=0)
    end_message_id: Mapped[int] = mapped_column(Integer, default=0)
    summary: Mapped[str] = mapped_column(Text, default="")
    key_facts: Mapped[list] = mapped_column(JSON, default=list)
    key_characters: Mapped[list] = mapped_column(JSON, default=list)
    emotional_tone: Mapped[str] = mapped_column(String(60), default="中性")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class SessionMemoryCorrectionModel(Base):
    """用户锁定的记忆纠正；不参与自动摘要生命周期。"""

    __tablename__ = "session_memory_corrections"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"), index=True)
    branch_id: Mapped[str | None] = mapped_column(String(80), nullable=True, index=True)
    content: Mapped[str] = mapped_column(Text)
    source_message_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)

    session: Mapped[ChatSessionModel] = relationship(back_populates="memory_corrections")


class SessionEventNodeModel(Base):
    """会话事件节点 — 构成因果树。"""

    __tablename__ = "session_event_nodes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"), index=True)
    character_id: Mapped[int | None] = mapped_column(ForeignKey("characters.id"), nullable=True)
    branch_id: Mapped[str] = mapped_column(String(80), default="main", index=True)
    parent_event_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    event_type: Mapped[str] = mapped_column(String(60), default="action")
    title: Mapped[str] = mapped_column(String(200), default="")
    description: Mapped[str] = mapped_column(Text, default="")
    importance: Mapped[int] = mapped_column(Integer, default=1)
    message_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    resolved: Mapped[bool] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class MessageAttachmentModel(Base):
    """消息附件，用于看图或后续扩展音频、文档。"""

    __tablename__ = "message_attachments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    message_id: Mapped[int] = mapped_column(ForeignKey("messages.id", ondelete="CASCADE"), index=True)
    asset_type: Mapped[str] = mapped_column(String(30), default="image")
    file_name: Mapped[str] = mapped_column(String(255), default="")
    mime_type: Mapped[str] = mapped_column(String(120), default="")
    storage_path: Mapped[str] = mapped_column(String(500), default="")
    generation_prompt: Mapped[str] = mapped_column(Text, default="")
    generation_model: Mapped[str] = mapped_column(String(120), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)

    message: Mapped[MessageModel] = relationship(back_populates="attachments")


class AppSettingModel(Base):
    """系统级可配置项。"""

    __tablename__ = "app_settings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    key: Mapped[str] = mapped_column(String(100), unique=True, index=True)
    value_json: Mapped[dict] = mapped_column(JSON, default=dict)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)


class JobRunModel(Base):
    """轻量任务运行记录，为后续长任务与异步任务预留基建。"""

    __tablename__ = "job_runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    job_type: Mapped[str] = mapped_column(String(80), index=True)
    status: Mapped[str] = mapped_column(String(40), default="pending", index=True)
    scope: Mapped[str] = mapped_column(String(80), default="system", index=True)
    target_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    input_json: Mapped[dict] = mapped_column(JSON, default=dict)
    output_json: Mapped[dict] = mapped_column(JSON, default=dict)
    error_message: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, index=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)
    started_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class PersonaModel(Base):
    """用户人设。用于自定义 {{user}} 宏展开和对话背景。"""

    __tablename__ = "personas"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(100), default="玩家")
    description: Mapped[str] = mapped_column(Text, default="")
    avatar_color: Mapped[str] = mapped_column(String(7), default="#53c7a8")
    avatar_image_path: Mapped[str] = mapped_column(String(500), default="")
    is_active: Mapped[bool] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)


class MessageBookmarkModel(Base):
    """消息书签。用于收藏重要消息。"""

    __tablename__ = "message_bookmarks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("chat_sessions.id", ondelete="CASCADE"), index=True)
    message_id: Mapped[int] = mapped_column(ForeignKey("messages.id", ondelete="CASCADE"), index=True)
    note: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class WorldEncyclopediaModel(Base):
    """世界百科库。一个百科库对应一个完整世界设定（如战锤40K、修仙世界等）。"""

    __tablename__ = "world_encyclopedias"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(200), unique=True, index=True)
    description: Mapped[str] = mapped_column(Text, default="")
    cover_image_path: Mapped[str] = mapped_column(String(500), default="")
    is_official: Mapped[bool] = mapped_column(Integer, default=0)
    world_prompt: Mapped[str] = mapped_column(Text, default="")
    gameplay_mode: Mapped[str] = mapped_column(String(80), default="自由剧情")
    anti_cheat_prompt: Mapped[str] = mapped_column(Text, default="")
    narrator_config_json: Mapped[dict] = mapped_column(JSON, default=dict)
    genre_tags: Mapped[str] = mapped_column(String(500), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)


class EncyclopediaEntryModel(Base):
    """百科条目。世界百科库中的单个词条。"""

    __tablename__ = "encyclopedia_entries"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    encyclopedia_id: Mapped[int] = mapped_column(ForeignKey("world_encyclopedias.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(300), index=True)
    entry_type: Mapped[str] = mapped_column(String(60), default="concept", index=True)
    summary: Mapped[str] = mapped_column(Text, default="")
    content: Mapped[str] = mapped_column(Text, default="")
    cover_image_path: Mapped[str] = mapped_column(String(500), default="")
    tags: Mapped[str] = mapped_column(Text, default="")
    related_entries: Mapped[str] = mapped_column(Text, default="")
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    is_featured: Mapped[bool] = mapped_column(Integer, default=0)
    meta_json: Mapped[dict] = mapped_column(JSON, default=dict)
    source_session_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    source_message_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    confidence: Mapped[str] = mapped_column(String(20), default="confirmed")
    last_referenced_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)


class EntryRelationModel(Base):
    """条目间关系 — 图数据库边。"""
    __tablename__ = "entry_relations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    encyclopedia_id: Mapped[int] = mapped_column(ForeignKey("world_encyclopedias.id", ondelete="CASCADE"), index=True)
    from_entry_id: Mapped[int] = mapped_column(ForeignKey("encyclopedia_entries.id", ondelete="CASCADE"), index=True)
    to_entry_id: Mapped[int] = mapped_column(ForeignKey("encyclopedia_entries.id", ondelete="CASCADE"), index=True)
    relation_type: Mapped[str] = mapped_column(String(60), default="关联", index=True)
    label: Mapped[str] = mapped_column(String(200), default="")
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class TimelineEventModel(Base):
    """时间线事件。"""
    __tablename__ = "timeline_events"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    encyclopedia_id: Mapped[int] = mapped_column(ForeignKey("world_encyclopedias.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(300), index=True)
    time_label: Mapped[str] = mapped_column(String(100), default="")
    time_order: Mapped[int] = mapped_column(Integer, default=0, index=True)
    entry_type: Mapped[str] = mapped_column(String(60), default="event", index=True)
    summary: Mapped[str] = mapped_column(Text, default="")
    content: Mapped[str] = mapped_column(Text, default="")
    tags: Mapped[str] = mapped_column(Text, default="")
    timeline_branch: Mapped[str] = mapped_column(String(100), default="main")
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc, onupdate=now_utc)


class EntryVersionModel(Base):
    """条目版本。"""
    __tablename__ = "entry_versions"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    entry_id: Mapped[int] = mapped_column(ForeignKey("encyclopedia_entries.id", ondelete="CASCADE"), index=True)
    version: Mapped[int] = mapped_column(Integer, default=1)
    title: Mapped[str] = mapped_column(String(300), default="")
    summary: Mapped[str] = mapped_column(Text, default="")
    content: Mapped[str] = mapped_column(Text, default="")
    tags: Mapped[str] = mapped_column(Text, default="")
    meta_snapshot_json: Mapped[dict] = mapped_column(JSON, default=dict)
    change_note: Mapped[str] = mapped_column(String(500), default="")
    created_by: Mapped[str] = mapped_column(String(100), default="system")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class RagDocumentModel(Base):
    """Data Bank 资料库文档。作用域隔离的资料来源。"""

    __tablename__ = "rag_documents"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    scope_type: Mapped[str] = mapped_column(String(30), default="global", index=True)
    scope_id: Mapped[int | None] = mapped_column(Integer, nullable=True, index=True)
    title: Mapped[str] = mapped_column(String(300), default="")
    source_kind: Mapped[str] = mapped_column(String(30), default="manual")
    source_url: Mapped[str] = mapped_column(String(1000), default="")
    verification_status: Mapped[str] = mapped_column(String(30), default="unverified")
    trust_level: Mapped[str] = mapped_column(String(30), default="manual")
    raw_text: Mapped[str] = mapped_column(Text, default="")
    chunk_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)


class RagChunkModel(Base):
    """资料文档的切块。含 embedding 向量索引。"""

    __tablename__ = "rag_chunks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    document_id: Mapped[int] = mapped_column(ForeignKey("rag_documents.id", ondelete="CASCADE"), index=True)
    chunk_index: Mapped[int] = mapped_column(Integer, default=0)
    content: Mapped[str] = mapped_column(Text, default="")
    token_count: Mapped[int] = mapped_column(Integer, default=0)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now_utc)
