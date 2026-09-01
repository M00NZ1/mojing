export const BATCH_GENERATE_MIN_COUNT = 5;
export const BATCH_GENERATE_MAX_COUNT = 20;

export function parseBatchGenerateCount(value: string): number | null {
  const count = Number(value.trim());
  if (!Number.isInteger(count)) return null;
  if (count < BATCH_GENERATE_MIN_COUNT || count > BATCH_GENERATE_MAX_COUNT) return null;
  return count;
}

export function hasBatchGenerateAnchor(
  anchorWeak: boolean,
  contextHint: string,
  allowWeakAnchor: boolean,
): boolean {
  return !anchorWeak || Boolean(contextHint.trim()) || allowWeakAnchor;
}
