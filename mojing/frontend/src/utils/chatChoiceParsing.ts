export function mergeRoundChoices(
  fromMessage: string[],
  maxCount: number,
): string[] {
  const cap = Math.min(Math.max(maxCount, 1), 8);
  return [...new Set(fromMessage.map((choice) => choice.trim()).filter(Boolean))].slice(0, cap);
}

type SplitReplyChoices = {
  content: string;
  choices: string[];
};

const choiceHeaderPattern = /^(?:#{1,6}\s*)?(?:\*\*|__)?\s*(?:可选行动|行动选项|后续选项|选项|下一步(?:行动)?|可供选择(?:的行动)?|你可以选择|choices?|options?)\s*(?:（[^）\r\n]{0,16}）|\([^\)\r\n]{0,16}\))?\s*[:：]?\s*(?:\*\*|__)?$/i;
const numberedChoicePattern = /^(?:\(?\d{1,2}\)?[.)）、：:]|（\d{1,2}）|\([一二三四五六七八]\)|（[一二三四五六七八]）|[一二三四五六七八][、.)）]|[A-Ha-h][.)、：:]|[-*•·])\s*(.+)$/;
const labelledChoicePattern = /^(?:选项|选择)\s*(?:\d{1,2}|[一二三四五六七八])\s*[-.)）、：:]\s*(.+)$/;

function trimChoiceMarkdown(raw: string): string {
  let value = raw.trim();
  while (value.startsWith('>')) value = value.slice(1).trimStart();
  if ((value.startsWith('**') && value.endsWith('**')) ||
      (value.startsWith('__') && value.endsWith('__'))) {
    value = value.slice(2, -2).trim();
  }
  return value;
}

function cleanChoiceLine(raw: string): string | null {
  const line = trimChoiceMarkdown(raw);
  const match = labelledChoicePattern.exec(line) ?? numberedChoicePattern.exec(line);
  return match ? trimChoiceMarkdown(match[1]) || null : null;
}

function uniqueChoices(items: string[]): string[] {
  return [...new Set(items.map((item) => item.trim()).filter(Boolean))].slice(0, 8);
}

export function splitReplyChoices(rawContent: string): SplitReplyChoices {
  const text = rawContent.trim();
  if (!text) return { content: '', choices: [] };

  const tagged = uniqueChoices(
    [...text.matchAll(/<OPTION\b[^>]*>([\s\S]*?)<\/OPTION>/gi)].map((match) => match[1]),
  );
  if (tagged.length > 0) {
    const content = text
      .replace(/<CHOICES\b[^>]*>[\s\S]*?<\/CHOICES>/gi, '')
      .replace(/<OPTION\b[^>]*>[\s\S]*?<\/OPTION>/gi, '')
      .trim()
      .replace(/\n{3,}/g, '\n\n');
    return { content, choices: tagged };
  }

  const wrapped = /<CHOICES\b[^>]*>([\s\S]*?)<\/CHOICES>/i.exec(text);
  if (wrapped) {
    const choices = uniqueChoices(
      wrapped[1]
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line && !choiceHeaderPattern.test(line))
        .map((line) => cleanChoiceLine(line) ?? trimChoiceMarkdown(line)),
    );
    if (choices.length >= 1) {
      const content = `${text.slice(0, wrapped.index)}${text.slice(wrapped.index + wrapped[0].length)}`
        .trim()
        .replace(/\n{3,}/g, '\n\n');
      return { content, choices };
    }
  }

  const lines = text.split(/\r?\n/);
  for (let headerIndex = lines.length - 1; headerIndex >= 0; headerIndex -= 1) {
    if (!choiceHeaderPattern.test(trimChoiceMarkdown(lines[headerIndex]))) continue;

    const consumed = new Set<number>([headerIndex]);
    const picked: string[] = [];
    let sawChoice = false;
    for (let index = headerIndex + 1; index < lines.length; index += 1) {
      const line = lines[index].trim();
      if (!line || /^```[A-Za-z0-9_-]*$/.test(line)) {
        consumed.add(index);
        continue;
      }
      const choice = cleanChoiceLine(line);
      if (choice) {
        picked.push(choice);
        consumed.add(index);
        sawChoice = true;
        continue;
      }
      if (!sawChoice) break;
      break;
    }
    const choices = uniqueChoices(picked);
    if (choices.length > 0) {
      return {
        content: lines.filter((_, index) => !consumed.has(index)).join('\n').trim().replace(/\n{3,}/g, '\n\n'),
        choices,
      };
    }
  }

  const picked: string[] = [];
  let index = lines.length - 1;
  while (index >= 0) {
    const line = lines[index].trim();
    if (!line) {
      index -= 1;
      continue;
    }
    const choice = cleanChoiceLine(line);
    if (choice) {
      picked.push(choice);
      index -= 1;
      continue;
    }
    if (picked.length > 0 && choiceHeaderPattern.test(line)) index -= 1;
    break;
  }

  const choices = uniqueChoices(picked.reverse());
  if (choices.length < 2) return { content: text, choices: [] };
  return {
    content: lines.slice(0, index + 1).join('\n').trim().replace(/\n{3,}/g, '\n\n'),
    choices,
  };
}

/** 从消息 structured_content 或正文提取动态选项。 */
export function extractChoicesFromMessage(message: {
  content?: string;
  structured_content?: Record<string, unknown>;
}): string[] {
  const structured = message.structured_content ?? {};
  if (structured.interrupted === true) return [];
  const fromStruct = structured.choices;
  if (Array.isArray(fromStruct)) {
    const choices = fromStruct.map((c) => String(c).trim()).filter(Boolean);
    if (choices.length > 0) return choices;
  }
  const content = message.content ?? '';
  return splitReplyChoices(content).choices;
}

/** 展示消息正文时剔除未选择的动态选项，旧消息无需改库即可恢复。 */
export function stripChoicesFromMessageContent(content: string): string {
  return splitReplyChoices(content).content;
}
