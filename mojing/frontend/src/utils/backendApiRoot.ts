/**
 * 与 Android `BackendApiRoot.resolveBackendApiRoot` 一致：
 * 公共对话根为典型 OpenAI 兼容网关（如以 `/v1` 结尾）时，不应再拼 `/api/worlds/...`，伴侣式导出等应隐藏。
 */
export function resolveBackendApiRoot(publicBaseUrl: string): string {
  const firstLine =
    publicBaseUrl
      .split(/\r?\n|;|,/)[0]
      ?.trim()
      ?.replace(/\/+$/, '') ?? '';
  let u = firstLine;
  if (!u) return '';
  const lower = u.toLowerCase();
  if (
    lower.endsWith('/v1') ||
    lower.includes('/compatible-mode/') ||
    lower.includes('/paas/v') ||
    lower.includes('/v1beta/')
  ) {
    return '';
  }
  if (u.endsWith('/api')) return u;
  if (!lower.includes('/api')) u = `${u}/api`;
  return u;
}

/** 墨境式 FastAPI 根可解析时，浏览器打开 `/worlds/templates/...` 下载才有意义。 */
export function isCompanionBackendConfigured(publicTextBaseUrl: string): boolean {
  return resolveBackendApiRoot(publicTextBaseUrl).length > 0;
}
