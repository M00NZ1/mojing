export function hasUnsavedEditMessage(content: string, originalContent: string): boolean {
  return content !== originalContent;
}

export function canSaveEditedMessage(
  content: string,
  originalContent: string,
  isSaving: boolean,
): boolean {
  if (isSaving) return false;
  const normalized = content.trim();
  return Boolean(normalized) && normalized !== originalContent.trim();
}
