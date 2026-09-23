export async function readChatEventStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onEvent: (event: Record<string, unknown>) => void,
): Promise<void> {
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let receivedDone = false;

  const consumeBlock = (block: string) => {
    const line = block.trim();
    if (!line.startsWith('data:')) return;
    const json = line.slice(5).trim();
    if (!json) return;
    const event: unknown = JSON.parse(json);
    if (!event || typeof event !== 'object' || Array.isArray(event)) {
      throw new Error('流式事件格式无效');
    }
    const typedEvent = event as Record<string, unknown>;
    if (typedEvent.type === 'done') receivedDone = true;
    onEvent(typedEvent);
  };

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
    const blocks = buffer.split('\n\n');
    buffer = blocks.pop() ?? '';
    for (const block of blocks) consumeBlock(block);

    if (done) {
      if (buffer.trim()) consumeBlock(buffer);
      if (!receivedDone) {
        throw new Error('流式连接提前结束，回复可能未完成。请检查对话记录。');
      }
      return;
    }
  }
}
