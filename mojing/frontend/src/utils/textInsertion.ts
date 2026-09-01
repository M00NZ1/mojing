export type TextInsertionResult = {
  text: string;
  cursor: number;
};

/** Inserts text at the browser selection range and places the cursor after it. */
export function insertTextAtSelection(
  text: string,
  selectionStart: number,
  selectionEnd: number,
  insertion: string,
): TextInsertionResult {
  const start = Math.max(0, Math.min(selectionStart, text.length));
  const end = Math.max(0, Math.min(selectionEnd, text.length));
  const rangeStart = Math.min(start, end);
  const rangeEnd = Math.max(start, end);

  return {
    text: `${text.slice(0, rangeStart)}${insertion}${text.slice(rangeEnd)}`,
    cursor: rangeStart + insertion.length,
  };
}
