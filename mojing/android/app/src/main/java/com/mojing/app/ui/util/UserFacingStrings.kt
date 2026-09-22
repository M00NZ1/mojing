package com.mojing.app.ui.util

/**
 * 用户可见文案（统一用语：API Key；不追加端别后缀）。
 */
object UserFacingStrings {
    /** 历史调用处保留；不再追加任何后缀 */
    fun appendAndroidIfNeeded(msg: String): String = msg

    fun personaNameRequired(): String = "请填写「我的名字」"

    fun characterNameRequired(): String = "请填写角色名（必填）"

    fun llmKeyMissingForAiComplete(): String =
        "请先在「设置」填写对话线路的 API Key，或在角色中填写专用 API Key 后再使用智能补全"

    fun saveSuccessGeneric(): String = "已保存"

    /** 只用于已经确认发生在远程 API 调用阶段的失败。 */
    fun remoteRequestFailed(e: Throwable?): String {
        val message = when (e) {
            is com.mojing.app.data.remote.LlmHttpException -> httpFailureHint(e.status)
            is com.mojing.app.data.remote.LlmProtocolException -> when (e.reason) {
                "output_limit" -> "模型输出达到上限，返回内容尚未完成。请缩短本次生成内容后重试。"
                "unsupported_stream" -> "当前接口不支持流式输出，请更换支持流式输出的模型或平台。"
                else -> "模型未返回完整内容，请重试。"
            }
            is java.net.SocketTimeoutException -> "等待模型响应超时，请重试。"
            is java.net.UnknownHostException -> "无法解析平台地址，请检查网络与接口地址。"
            is java.net.SocketException, is java.io.EOFException -> "网络连接中断，请检查网络后重试。"
            is java.io.IOException -> "网络请求未完成，请检查连接后重试。"
            else -> streamErrorDetail(e?.message)
        }
        return if (message.startsWith("请求失败")) message else "请求失败：$message"
    }

    private fun httpFailureHint(status: Int): String = when (status) {
        400, 422 -> "模型或请求参数不受支持（HTTP $status），请检查模型配置。"
        401 -> "平台认证失败（HTTP 401）。请核对本次使用的平台、接口地址和 API Key；跟随设置时也需检查角色及会话中的专用配置。"
        402 -> "平台额度不足（HTTP 402），请检查可用余额。"
        403 -> "平台拒绝访问（HTTP 403），请检查 Key 和模型权限。"
        404 -> "找不到指定的模型或接口（HTTP 404），请检查模型名称和地址。"
        429 -> "平台限流或额度已用尽（HTTP 429），请检查平台用量或稍后重试。"
        in 500..599 -> "平台暂时不可用（HTTP $status），请稍后重试。"
        else -> "平台请求失败（HTTP $status），请检查模型配置。"
    }

    private fun httpStatus(message: String): Int? =
        Regex("\\bHTTP\\s+([1-5][0-9]{2})\\b", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)?.toIntOrNull()

    fun localLoadFailed(subject: String): String = "${subject}未能从本机读取，请重试。"

    fun localSaveFailed(subject: String): String = "${subject}未能保存到本机，请重试。"

    fun generatedCoverLocalSaveFailed(): String = "图片已生成，但封面未能保存到本机，请重试。"

    fun operationFailed(): String = "操作失败，请重试。"

    fun chatSendNeedContent(): String = "请先输入文字或选择图片后再发送"

    fun chatNoParticipant(): String =
        "本局还没有角色：点右上角菜单打开侧栏 →「参与者」→「+」，添加至少一名角色后再发消息。"

    fun chatCharacterNotFound(): String = "绑定的角色已不存在，请在侧栏重新添加"

    fun chatApiKeyMissing(): String = "请先在「设置」填写 API Key，或在角色中填写 API Key"

    fun chatMainModelMissing(): String =
        "未填写主对话模型 id：请在角色「模型名称」或「设置」的公共模型中填写后再发送"

    fun chatStreamTimeout(): String =
        "对话请求超时（约 2 分钟未完成）。可检查网络、核对设置里的服务地址与 Key，或稍后再试。"

    fun ttsContentEmptyAfterClean(): String =
        "没有适合朗读的正文（多为表情或格式符号），已跳过朗读"

    fun imageGenKeyMissing(): String =
        "请先在「设置」填写公共 API Key；若已填写仍无法生图，可在侧栏「世界」填写本场配图 Key，或在角色中开启配图并填写专用 Key。"

    /** 专用配图线路失败后，已用公共线路重试并成功（仅作提示，可手动清除） */
    fun imageGenRetriedWithPublicOk(): String =
        "专用配图线路请求失败，已自动改用公共配图并完成。"

    fun imageSaveFailed(): String = "图片保存失败"

    /** 流式/接口错误信息（常见英文/厂商报错映射为中文 + 下一步） */
    fun streamErrorDetail(msg: String?): String {
        val raw = msg?.trim().takeUnless { it.isNullOrEmpty() } ?: return "请求失败，请稍后再试。"
        httpStatus(raw)?.let { return httpFailureHint(it) }
        val l = raw.lowercase()
        val hint = when {
            "unknownhostexception" in l || "unresolved address" in l -> "无法解析服务器地址。请检查网络或填写的服务根地址是否正确。"
            "connectexception" in l || "socketexception" in l || "eofexception" in l -> "网络连接中断。请检查网络后重试。"
            Regex("\\b401\\b").containsMatchIn(raw) || "unauthorized" in l -> httpFailureHint(401)
            Regex("\\b403\\b").containsMatchIn(raw) && ("forbidden" in l || "http" in l) -> "接口拒绝访问（403）。请检查 Key 权限或账号策略。"
            Regex("\\b404\\b").containsMatchIn(raw) && ("model" in l || "not found" in l || "http" in l) -> "找不到指定的模型或资源。请在设置或角色中核对模型名称。"
            Regex("\\b429\\b").containsMatchIn(raw) || "rate limit" in l || "too many requests" in l -> "请求过于频繁，请稍等几秒再试。"
            "timeout" in l || (raw.contains("timed", ignoreCase = true) && raw.contains("out", ignoreCase = true)) ->
                "连接或读取超时。请检查网络或稍后再试。"
            "unknownhost" in l || "unable to resolve" in l -> "无法解析服务器地址。请检查网络或填写的服务根地址是否正确。"
            "connection refused" in l -> "连接被拒绝。请确认服务已启动且地址与端口正确。"
            "failed to connect" in l || "connect failed" in l -> "无法连上服务器。请检查网络、VPN 或防火墙。"
            "ssl" in l || "certificate" in l -> "SSL / 证书验证失败。请检查 HTTPS 地址或系统证书。"
            "connection abort" in l || "connection reset" in l || "ended before completion" in l -> "网络连接中断，请重试。"
            Regex("http\\s+5[0-9]{2}", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "平台暂时不可用，请稍后重试。"
            "socket" in l && "closed" in l -> "网络连接中断。请重试。"
            else -> null
        }
        return hint ?: raw
    }

    /** 思考模式沿用实际错误分类，不将认证、网络或限流误判为能力不支持。 */
    fun streamErrorThinkMaxRoute(msg: String?): String = "思考/Max：${streamErrorDetail(msg)}"

    fun entryTitleRequired(): String = "请填写条目标题（必填）"

    fun entryTitleRequiredForAi(): String = "请先填写条目标题，再使用智能补全"

    fun entrySaved(): String = "条目已保存"

    fun entryAiNoNewFields(): String = "未生成新的可写入内容，可补充标题或摘要后再试"

    fun entryAiApplied(): String = "智能补全已写入表单，请检查后点保存"

    fun entryTitleRequiredForCover(): String = "请先填写条目标题，再生成封面"

    fun backendApiRootMissingForCover(): String =
        "请先在「设置」填写关联服务根地址，再生成封面"

    fun entryCoverNoUrl(): String = "未获取到图片地址，请检查配图 API Key、服务地址与模型"

    fun entryCoverDownloadFailed(): String = "下载封面图片失败"

    fun entryCoverSaveFailed(): String = "封面裁切保存失败"

    fun entryCoverGeneratedSaveHint(): String = "已生成条目封面，请点击保存以写入本机"

    fun templateLabelRequired(): String = "请填写模板名称（必填）"

    fun templateIdRequired(): String = "无法生成模板标识，请重新进入编辑页"

    fun templateAiNoChange(): String = "未写入新的摘要或设定，可先填写模板名称再试"

    fun templateAiApplied(): String = "智能补全已写入，请检查后保存"

    fun exportWriteFailed(): String = "写入导出文件失败，目标文件可能不完整，请重新选择文件"

    fun importReadFailed(): String = "未能读取所选文件"

    /** 列表/详情页导出 JSON 等成功 */
    fun exportSuccess(): String = "导出成功"

    /** 删除角色、百科、模板、会话等后的轻量确认 */
    fun itemDeleted(label: String): String = "已删除「$label」"

    fun profileSaved(): String = "个人资料已保存"

    /** 用户选择保存路径后写入导出文件 */
    fun exportDocumentSaved(fileName: String): String = "已保存「$fileName」"

    fun documentWriteFailed(msg: String?): String =
        "写入文件失败，目标文件可能不完整: ${msg?.trim().takeUnless { it.isNullOrEmpty() } ?: "未知原因"}"

    fun entryHistoryVersionLoaded(): String = "已载入历史版本，请检查后保存"

    fun encyclopediaCreated(): String = "已新建百科库，可在列表中点入编辑名称"

    fun characterDraftCreated(): String = "已新建角色，请在列表中点入编辑"

    fun sessionCreated(): String = "对话已创建"

    fun blankSessionCreated(): String =
        "已创建空白对话。请打开侧栏 →「参与者」→「+」添加至少一名角色后再发消息。"

    fun templateDraftCreated(): String = "已新建模板，请在列表中点入编辑"

    fun entryCreatedListHint(): String = "已新建条目，请在列表中点入编辑"

    fun timelineTitleRequired(): String = "请填写时间轴事件标题"

    fun relationEndpointsMustDiffer(): String = "关系的起点与终点不能是同一条目"

    fun batchGenerateInProgress(): String = "正在批量生成条目…"

    fun batchGenerateSuccess(count: Int): String = "成功生成 $count 个条目"

    fun micPermissionRequired(): String = "需要麦克风权限才能语音输入"

    fun speechRecognitionUnavailable(): String =
        "本机没有可用的语音听写应用，已取消。可改用键盘输入或安装系统语音服务。"

    fun copiedToClipboard(): String = "已复制到剪贴板"
    fun messageHasNoCopyableText(): String = "这条消息没有可复制的正文"
    fun messageHasNoQuotableText(): String = "这条消息没有可引用的正文"

    fun bookmarkUpdated(): String = "已更新收藏"

    fun bookmarkJumpNotFound(): String = "未在主分支列表中找到该消息"

    fun searchHitNotOnCurrentBranch(): String = "该条不在当前分支展示列表中"

    fun imageGenPromptRequired(): String = "请先填写画面描述"

    fun characterAiPersonaApplied(): String = "智能补全已写入人设，请检查后保存"

    fun characterAiNoNewPersona(): String = "未生成新的可写入内容，可先填写角色名或补充关键词后再试"

    fun characterAiPersonaFailed(detail: String?): String {
        val friendly = streamErrorDetail(detail)
        return if (friendly == "请求失败") "人设补全失败，请检查 API Key 与模型配置后重试" else "人设补全失败：$friendly"
    }

    fun characterAiPersonaQueuedLong(): String =
        "人设任务仍在进行（可能在排队）。可打开任务列表查看；超过 15 分钟仍无结果请检查网络与 API Key。"

    fun sessionSearchNoMatch(): String = "没有标题匹配的对话"

    fun sessionSearchEmptyLibrary(): String = "还没有对话，无法按标题搜索"

    fun chatMultiCharacterStoppedAt(characterName: String, detail: String?): String {
        val who = characterName.trim().ifBlank { "角色" }
        val d = detail?.trim().orEmpty()
        return if (d.isNotEmpty()) "多角色轮次停在「$who」：$d" else "多角色轮次停在「$who」，后续角色未生成"
    }

    fun exportCharacterNotLoaded(): String = "无法导出：角色未加载"

    fun exportSummaryNeedsGlobalKey(): String = "摘要导出需要先在设置中填写全局 API Key"

    fun exportFailedDetail(msg: String?): String =
        "导出失败: ${msg?.trim().takeUnless { it.isNullOrEmpty() } ?: "未知错误"}"

    fun cardImageProcessFailed(): String = "无法处理所选图片，请换一张或缩小尺寸后重试"
}
