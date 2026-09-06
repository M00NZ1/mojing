from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from .config import BASE_DIR, STORAGE_DIR, settings
from .database import Base, SessionLocal, engine
from .logger import logger, setup_logging
from .middleware.rate_limit import RateLimitMiddleware
from .routes.characters import router as characters_router
from .routes.channels import router as channels_router
from .routes.encyclopedia import router as encyclopedia_router
from .routes.ai import router as ai_router
from .routes.output_rules import router as output_rules_router
from .routes.files import router as files_router
from .routes.rag import router as rag_router
from .routes.personas import router as personas_router
from .routes.tasks import router as tasks_router
from .routes.assets import router as assets_router
from .routes.images import router as images_router
from .routes.jobs import router as jobs_router
from .routes.providers import router as providers_router
from .routes.prompts import router as prompts_router
from .routes.sessions import router as sessions_router
from .routes.system import router as system_router
from .routes.voices import router as voices_router
from .routes.actions import router as actions_router
from .routes.worlds import router as worlds_router
from .routes.workbench import router as workbench_router
from .routes.story_simulations import router as story_simulations_router
from .routes.costs import router as costs_router
from .routes.expressions import router as expressions_router
from .routes.timeline import router as timeline_router
from .services.legacy_import import bootstrap_legacy_data
from .services.public_storage import mount_public_storage
from .services.starter_catalog_service import install_starter_catalog
from .services.builtin_media_sync import sync_builtin_media_pack
from .services.channel_service import ChannelStorageError
from .services.crypto_service import SecretStorageError


FRONTEND_DIST = BASE_DIR.parent / "frontend" / "dist"


OPENAPI_TAGS = [
    {"name": "会话", "description": "会话管理：创建、发送消息、分支、流式生成、参与者管理等"},
    {"name": "人物", "description": "角色管理：增删改查、头像上传、角色卡导入导出、收藏等"},
    {"name": "角色表情", "description": "角色表情/立绘管理：上传、删除、列表查询"},
    {"name": "世界库", "description": "世界模板管理：创建世界设定、Lore 条目增删改查、导入导出等"},
    {"name": "世界百科", "description": "百科库管理：创建百科、条目增删改查、模板引导导入等"},
    {"name": "设定工坊", "description": "世界设定构建工具：AI 生成、长文本导入、Prompt 改写等"},
    {"name": "世界时间线", "description": "会话世界时间线管理与事件记录"},
    {"name": "知识库检索", "description": "Data Bank RAG 知识库检索与上下文注入"},
    {"name": "提示词库", "description": "全局提示词模板库管理：创建、修订、版本管理"},
    {"name": "AI 能力层", "description": "底层 AI 能力接口：模型列表、直接调用等"},
    {"name": "API 渠道", "description": "第三方 API 渠道管理与负载均衡"},
    {"name": "用户人设", "description": "用户个人资料与偏好设定管理"},
    {"name": "输出后处理", "description": "AI 输出内容的后处理过滤规则管理"},
    {"name": "文件管理", "description": "文件上传与管理"},
    {"name": "资源中心", "description": "内置资源中心：角色立绘、世界封面等素材管理"},
    {"name": "声色", "description": "语音声色管理：绑定、合成、试听、上传参考音频等"},
    {"name": "图像生成", "description": "图像生成服务：文生图、表情包等"},
    {"name": "系统", "description": "系统状态监控、配置查看、日志等"},
    {"name": "成本统计", "description": "LLM 调用成本统计与用量分析"},
    {"name": "安全宏", "description": "预定义安全宏操作：总结事件、检查冲突、沉淀百科等"},
    {"name": "任务", "description": "轻量任务运行记录查询"},
    {"name": "异步任务", "description": "LLM 调试事件等轻量端点"},
]

TAG_MAP = {tag["name"]: tag for tag in OPENAPI_TAGS}


def create_app() -> FastAPI:
    """应用工厂。"""

    setup_logging()
    logger.info("应用启动", extra={"app": settings.app_name})

    app = FastAPI(
        title="墨境",
        summary="AI 角色扮演对话平台 · 本地优先",
        description="""
# 墨境

AI 驱动的角色扮演对话平台，**本地优先**设计，完整开源。

## 核心功能
- **角色扮演聊天**：多角色群聊、流式 SSE 生成、分支对话、Swipe 换一批
- **角色管理**：独立 API 配置、人设编辑、角色卡导入导出、表情/立绘管理
- **世界设定**：世界模板、Lorebook 关键词注入、百科库、RAG 知识库检索
- **设定工坊**：AI 自动生成世界设定、长文抽取、Prompt 改写
- **语音声色**：绑定声色、参考音频上传、语音合成
- **图像生成**：文生图、表情包生成
- **系统监控**：成本统计、Token 用量、调用追踪

## 技术栈
- **框架**：FastAPI + SQLAlchemy 2.0 + SQLite
- **前端**：React 18 + Vite 5 + TypeScript
- **实时通信**：Server-Sent Events (SSE) 流式生成

> 详细 API 说明请展开各标签查看。
""",
        version="2.0.0",
        contact={"name": "墨境"},
        license_info={"name": "MIT"},
        openapi_tags=OPENAPI_TAGS,
        docs_url="/docs",
        redoc_url="/redoc",
    )

    @app.exception_handler(SecretStorageError)
    async def secret_storage_error_handler(_request: Request, exc: SecretStorageError):
        return JSONResponse(status_code=503, content={"detail": str(exc)})

    @app.exception_handler(ChannelStorageError)
    async def channel_storage_error_handler(_request: Request, exc: ChannelStorageError):
        return JSONResponse(status_code=500, content={"detail": str(exc)})
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_origin_regex=settings.cors_origin_regex or None,
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(RateLimitMiddleware)

    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        bootstrap_legacy_data(db, Path(__file__).resolve().parents[2])
        install_starter_catalog(db)
        from .services.world_checkpoint_service import recover_world_jobs
        recover_world_jobs(db)

    app.include_router(sessions_router, prefix=settings.api_prefix)
    app.include_router(assets_router, prefix=settings.api_prefix)
    app.include_router(channels_router, prefix=settings.api_prefix)
    app.include_router(characters_router, prefix=settings.api_prefix)
    app.include_router(jobs_router, prefix=settings.api_prefix)
    app.include_router(providers_router, prefix=settings.api_prefix)
    app.include_router(prompts_router, prefix=settings.api_prefix)
    app.include_router(system_router, prefix=settings.api_prefix)
    app.include_router(tasks_router, prefix=settings.api_prefix)
    app.include_router(encyclopedia_router, prefix=settings.api_prefix)
    app.include_router(ai_router, prefix=settings.api_prefix)
    app.include_router(images_router, prefix=settings.api_prefix)
    app.include_router(output_rules_router, prefix=settings.api_prefix)
    app.include_router(costs_router, prefix=settings.api_prefix)
    app.include_router(expressions_router, prefix=settings.api_prefix)
    app.include_router(timeline_router, prefix=settings.api_prefix)
    app.include_router(files_router, prefix=settings.api_prefix)
    app.include_router(rag_router, prefix=settings.api_prefix)
    app.include_router(personas_router, prefix=settings.api_prefix)
    app.include_router(voices_router, prefix=settings.api_prefix)
    app.include_router(worlds_router, prefix=settings.api_prefix)
    app.include_router(workbench_router, prefix=settings.api_prefix)
    app.include_router(story_simulations_router, prefix=settings.api_prefix)
    app.include_router(actions_router, prefix=settings.api_prefix)

    STORAGE_DIR.mkdir(parents=True, exist_ok=True)
    sync_builtin_media_pack()
    mount_public_storage(app, STORAGE_DIR)

    @app.get("/health")
    def health():
        return {"ok": True}

    api_fallback_methods = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"]

    @app.api_route(settings.api_prefix, methods=api_fallback_methods, include_in_schema=False)
    def missing_api_root():
        raise HTTPException(status_code=404, detail="API endpoint not found")

    @app.api_route(
        f"{settings.api_prefix}/{{full_path:path}}",
        methods=api_fallback_methods,
        include_in_schema=False,
    )
    def missing_api_endpoint(full_path: str):
        raise HTTPException(status_code=404, detail="API endpoint not found")
    if FRONTEND_DIST.exists():
        from fastapi.responses import FileResponse

        app.mount("/assets", StaticFiles(directory=FRONTEND_DIST / "assets"), name="frontend_assets")
        app.mount("/icons", StaticFiles(directory=FRONTEND_DIST / "icons"), name="frontend_icons")

        @app.get("/{full_path:path}")
        def serve_frontend(full_path: str):
            return FileResponse(FRONTEND_DIST / "index.html")

    return app


app = create_app()
