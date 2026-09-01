/**
 * 用户可见错误文案（与 Android `UserFacingStrings.streamErrorDetail` 对齐）。
 */

export function streamErrorDetail(msg: string | null | undefined): string {
  const raw = (msg ?? '').trim();
  if (!raw) return '请求失败，请稍后再试。';
  const l = raw.toLowerCase();
  let hint: string | null = null;
  if (raw.includes('401') || l.includes('unauthorized')) {
    hint = 'API Key 无效、过期或未授权。请到「设置」或角色资料中检查 Key。';
  } else if (raw.includes('403') && l.includes('forbidden')) {
    hint = '接口拒绝访问（403）。请检查 Key 权限或账号策略。';
  } else if (raw.includes('404') && (l.includes('model') || l.includes('not found'))) {
    hint = '找不到指定的模型或资源。请在设置或角色中核对模型名称。';
  } else if (raw.includes('429') || l.includes('rate limit') || l.includes('too many requests')) {
    hint = '请求过于频繁，请稍等几秒再试。';
  } else if (
    l.includes('timeout') ||
    (raw.toLowerCase().includes('timed') && raw.toLowerCase().includes('out'))
  ) {
    hint = '连接或读取超时。请检查网络或稍后再试。';
  } else if (l.includes('unknownhost') || l.includes('unable to resolve')) {
    hint = '无法解析服务器地址。请检查网络或填写的服务根地址是否正确。';
  } else if (l.includes('connection refused')) {
    hint = '连接被拒绝。请确认服务已启动且地址与端口正确。';
  } else if (l.includes('failed to connect') || l.includes('connect failed')) {
    hint = '无法连上服务器。请检查网络、VPN 或防火墙。';
  } else if (l.includes('ssl') || l.includes('certificate')) {
    hint = 'SSL / 证书验证失败。请检查 HTTPS 地址或系统证书。';
  } else if (l.includes('socket') && l.includes('closed')) {
    hint = '网络连接中断。请重试。';
  } else if (raw.includes('402') || l.includes('insufficient_quota')) {
    hint = 'API 额度不足，请检查账户余额或套餐。';
  }
  const tail = raw.length > 120 ? `${raw.slice(0, 120)}…` : raw;
  return hint ? `${hint}（详情：${tail}）` : raw;
}

export function chatStreamTimeout(): string {
  return '对话请求超时（约 2 分钟未完成）。可检查网络、核对设置里的服务地址与 Key，或稍后再试。';
}

export function chatNoParticipant(): string {
  return '本局还没有角色：在侧栏「参与者」中添加至少一名角色后再发消息。';
}

/** 将网络 / 浏览器 / API 错误转成用户可读中文 */
export function friendlyFetchError(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err ?? '');
  if (/Failed to fetch|NetworkError|Load failed|ECONNREFUSED|fetch/i.test(raw)) {
    return '无法连接服务器。请确认本机后端已启动，并检查前端环境变量 VITE_API_BASE 是否指向正确地址。';
  }
  const mapped = streamErrorDetail(raw);
  return mapped || '操作失败，请稍后重试';
}

export function isAbortError(err: unknown): boolean {
  return err instanceof Error && err.name === 'AbortError';
}
