"""为 schemas.py 所有模型和字段添加中文 description。"""
import re

SCHEMAS_PATH = __file__.rsplit("\\scripts\\", 1)[0] + "\\app\\schemas.py"

FIELD_DESCRIPTIONS = {
    "id": "唯一标识",
    "name": "名称",
    "provider": "服务提供商",
    "reference_audio_path": "参考音频路径",
    "language": "语言",
    "description": "描述",
    "created_at": "创建时间",
    "updated_at": "更新时间",
    "persona_prompt": "角色性格设定文本",
    "api_key": "API 密钥（加密存储）",
    "api_base_url": "API 接口地址",
    "model_name": "AI 模型名称",
    "temperature": "采样温度，越高越有创意（0.0~2.0）",
    "max_tokens": "最大输出 Token 数",
    "top_p": "核采样概率阈值（0.0~1.0）",
    "top_k": "候选词数量（0 表示不限制）",
    "frequency_penalty": "重复词惩罚系数（-2.0~2.0）",
    "presence_penalty": "话题重复惩罚系数（-2.0~2.0）",
    "repetition_penalty": "复读惩罚系数（0.1~2.0）",
    "avatar_color": "头像背景颜色（十六进制）",
    "avatar_image_path": "头像图片路径",
    "voice_profile_id": "绑定的声色档案 ID",
    "character_id": "角色 ID",
    "favorite": "是否收藏",
    "recommended_voice_names": "推荐声线名称列表",
    "source_filename": "源文件名",
    "raw_persona_text": "原始设定文本",
    "character_card_json": "人物卡 JSON 结构数据",
    "character_card_markdown": "人物卡 Markdown 格式数据",
    "extracted_at": "抽取时间",
    "source_text": "源设定文本",
    "merge_into_persona_prompt": "是否合并到角色人设提示词",
    "session_id": "会话 ID",
    "encyclopedia_id": "百科库 ID",
    "world_prompt": "世界设定提示词",
    "template_id": "世界模板标识",
    "gameplay_mode": "玩法模式",
    "narrator_enabled": "是否启用旁白推进",
    "narrator_name": "旁白角色名称",
    "choice_generation_enabled": "是否启用选项生成",
    "max_choice_count": "最大选项数量",
    "suggested_choices_json": "建议选项列表",
    "anti_cheat_enabled": "是否启用防越权检测",
    "anti_cheat_prompt": "防越权检测提示词",
    "world_timeline_json": "世界时间线数据",
    "title": "标题",
    "summary": "摘要",
    "message_count": "消息数量",
    "participant_count": "参与者数量",
    "world": "世界配置",
    "label": "显示标签",
    "sort_order": "排序序号",
    "question": "问题",
    "max_auto_speakers": "最大自动发言人数",
    "auto_select_speakers": "是否自动选择发言人",
    "include_narrator": "是否包含旁白",
    "branch_id": "分支标识",
    "parent_branch_id": "父分支标识",
    "source_message_id": "源消息 ID",
    "source_message_preview": "源消息预览",
    "source_created_at": "源消息创建时间",
    "latest_created_at": "最新消息时间",
    "depth": "分支深度",
    "message_id": "消息 ID",
    "latest_message_id": "最新消息 ID",
    "parent_message_id": "父消息 ID",
    "regenerated_from_message_id": "重新生成来源消息 ID",
    "swipe_group_id": "Swipe 分组标识",
    "speaker_type": "发言者类型（user/character/narrator）",
    "character_name": "角色名称",
    "character_avatar_path": "角色头像路径",
    "content": "内容文本",
    "structured_content": "结构化内容（含 NARRATION/THOUGHT/SPEECH）",
    "attachments": "附件列表",
    "items": "数据项列表",
    "next_cursor": "下一页游标",
    "snippet": "搜索结果片段",
    "character_ids": "角色 ID 列表",
    "reason": "原因说明",
    "user_message": "用户消息内容",
    "clips": "音频片段列表",
    "text": "文本内容",
    "url": "文件 URL 路径",
    "kind": "类型",
    "instruction": "改写指令",
    "chunk_size": "分块大小",
    "result": "改写结果",
    "dynamic_state_json": "动态状态数据",
    "relations_json": "关系数据",
    "private_facts_json": "私有事实列表",
    "event_log_json": "事件日志列表",
    "last_compacted_message_id": "最后一次压缩的消息 ID",
    "category": "分类",
    "cover_image_path": "封面图片路径",
    "suggested_choices": "建议选项列表",
    "is_builtin": "是否为内置模板",
    "world_template_id": "世界模板 ID",
    "entry_type": "条目类型",
    "keywords_json": "触发关键词列表",
    "is_core": "是否为核心条目（始终注入）",
    "world_type": "世界类型",
    "core_theme": "核心主题",
    "tone": "基调",
    "extra_requirements": "额外要求",
    "person_name_count": "人物名称数量",
    "place_name_count": "地点名称数量",
    "item_name_count": "物品名称数量",
    "lore_entry_count": "Lore 条目数量",
    "auto_save": "是否自动保存",
    "category_hint": "分类提示",
    "quality_report": "质量报告",
    "saved_template": "已保存的模板",
    "lore_entries": "Lore 条目列表",
    "encyclopedia_entries": "百科条目列表",
    "generated_world_prompt": "生成的世界提示词",
    "generated_template_id": "生成的模板 ID",
    "generated_template_label": "生成的模板名称",
    "tags": "标签（逗号分隔）",
    "related_entries": "关联条目",
    "is_featured": "是否精选",
    "verified": "是否已验证",
    "canon_scope": "正典范围",
    "canon_conflicts": "正典冲突列表",
    "trust_level": "可信度等级",
    "unknown_fields": "未知字段列表",
    "activation_mode": "激活模式",
    "change_note": "变更说明",
    "dry_run": "是否仅预览（不实际写入）",
    "overwrite_existing": "是否覆盖已有条目",
    "max_extract_chars": "最大提取字符数",
    "imported_count": "已导入数量",
    "skipped_count": "已跳过数量",
    "failed_count": "失败数量",
    "entry_id": "条目 ID",
    "source": "来源",
    "warnings": "警告信息列表",
    "created_count": "创建数量",
    "sources": "导入来源列表",
    "api_url": "API 获取地址",
    "source_license": "来源许可协议",
    "source_url": "来源 URL",
    "is_active": "是否激活",
    "ok": "是否正常",
    "version": "版本号",
    "uptime_seconds": "运行时长（秒）",
    "database_size_bytes": "数据库大小（字节）",
    "config": "配置信息",
    "voice_service": "语音服务配置",
    "provider_id": "提供商标识",
    "base_url": "基础 URL",
    "models": "模型列表",
    "model_param_name": "模型参数名称",
    "context_note": "上下文说明",
    "notes": "备注",
    "supports_vision": "是否支持视觉",
    "supports_audio": "是否支持音频",
    "recommended": "是否推荐",
    "file_name": "文件名",
    "mime_type": "MIME 类型",
    "storage_path": "存储路径",
    "file_path": "文件路径",
    "query": "查询文本",
    "scopes": "检索范围列表",
    "token_budget": "Token 预算",
    "chunks": "检索结果块列表",
    "relevance_score": "相关度分数",
    "persona_name": "人设名称",
    "job_type": "任务类型",
    "status": "任务状态",
    "progress": "进度（0~100）",
    "error": "错误信息",
    "params": "参数",
    "pattern": "匹配模式（正则）",
    "replacement": "替换内容",
    "enabled": "是否启用",
    "priority": "优先级",
    "output_rules": "输出规则列表",
    "task_type": "任务类型",
    "task_id": "任务标识",
    "eta_seconds": "预计剩余秒数",
    "expression": "表情标识",
    "image_path": "图片路径",
    "expression_id": "表情 ID",
    "event_type": "事件类型",
    "event_data": "事件数据",
    "turn_number": "对话轮次",
    "timestamp": "时间戳",
    "action_name": "操作名称",
    "limit": "返回数量限制",
    "offset": "偏移量",
    "q": "搜索关键词",
    "days": "统计天数",
    "total_tokens": "总 Token 数",
    "prompt_tokens": "Prompt Token 数",
    "completion_tokens": "Completion Token 数",
    "estimated_cost": "估算成本（美元）",
    "duration_ms": "耗时（毫秒）",
    "success": "是否成功",
    "by_model": "按模型分组统计",
    "total_cost_usd": "总成本（美元）",
    "total_calls": "调用总次数",
    "failed_calls": "失败次数",
    "records": "调用记录列表",
    "character": "角色信息",
    "participants": "参与者列表",
    "branches": "分支列表",
    "memory_hits": "记忆命中记录",
    "lore_hits": "Lore 命中记录",
    "encyclopedia_hits": "百科命中记录",
    "prompt_debug": "Prompt 调试信息",
    "remaining_tokens": "剩余 Token 数",
    "model_context_limit": "模型上下文限制",
    "system_prompt_tokens": "系统提示词 Token 数",
    "character_tokens": "角色设定 Token 数",
    "memory_tokens": "记忆状态 Token 数",
    "lore_tokens": "Lore Token 数",
    "encyclopedia_tokens": "百科 Token 数",
    "history_tokens": "历史消息 Token 数",
    "world_template": "世界模板",
    "encyclopedia": "百科库",
    "message": "消息对象",
    "state": "状态对象",
}

# 读取文件
with open(SCHEMAS_PATH, "r", encoding="utf-8") as f:
    text = f.read()

# 逐行处理
lines = text.split("\n")
modified = False
i = 0

while i < len(lines):
    line = lines[i]
    stripped = line.strip()

    # 检测 class 定义 → 加 docstring
    cm = re.match(r"^class (\w+)\(BaseModel", stripped)
    if cm:
        class_name = cm.group(1)
        # 检查下一行是否已有 docstring 或 model_config
        if i + 1 < len(lines):
            next_stripped = lines[i + 1].strip()
            if not next_stripped.startswith('"""') and not next_stripped.startswith("model_config") and not next_stripped == "pass":
                indent = "    "
                lines.insert(i + 1, f'{indent}"""{class_name} 数据模型。"""\n')
                modified = True
                print(f"[Docstring] {class_name}")
                i += 2
                continue
            elif next_stripped.startswith('"""'):
                i += 1
                continue

    # 检测字段行（缩进4空格 + 字段名: 类型）
    fm = re.match(r"^    (\w+):\s*(\S[^#]*?)(?:#.*)?$", line)
    if fm:
        field_name = fm.group(1)
        field_type_part = fm.group(2).strip()
        # 跳过特殊情况
        if field_name == "model_config" or "Field(" in line or "description=" in line:
            i += 1
            continue
        # 查找是否有默认值
        has_default = "=" in field_type_part
        desc = FIELD_DESCRIPTIONS.get(field_name)
        if desc and not has_default:
            # 处理可选类型：int | None = None
            none_match = re.match(r"([\w\[\] ]+) \| None", field_type_part)
            if none_match:
                base_type = none_match.group(1).strip()
                lines[i] = f"    {field_name}: {base_type} | None = Field(default=None, description=\"{desc}\")\n"
            else:
                lines[i] = f"    {field_name}: {field_type_part} = Field(description=\"{desc}\")\n"
            modified = True
            print(f"[Field] {field_name} → {desc}")
        elif desc and has_default:
            # 已有默认值的：voice_profile_id: int | None = None
            parts = field_type_part.split("=", 1)
            ftype = parts[0].strip()
            fdefault = parts[1].strip()
            if "| None" in ftype or "|None" in ftype:
                lines[i] = f"    {field_name}: {ftype} = Field(default={fdefault}, description=\"{desc}\")\n"
            else:
                lines[i] = f"    {field_name}: {ftype} = Field(default={fdefault}, description=\"{desc}\")\n"
            modified = True
            print(f"[Field] {field_name} → {desc}")

    i += 1

if modified:
    with open(SCHEMAS_PATH, "w", encoding="utf-8") as f:
        f.writelines(lines)
    print(f"\n✅ schemas.py 中文化完成！")
else:
    print("\n无需修改。")
