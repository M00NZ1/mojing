// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { readChatEventStream } from './chatEventStream.ts';

function readerFor(...chunks: string[]): ReadableStreamDefaultReader<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  }).getReader();
}

async function expectStream(chunks: string[], expectedTypes: string[]) {
  const types: string[] = [];
  await readChatEventStream(readerFor(...chunks), (event) => types.push(String(event.type)));
  if (types.join(',') !== expectedTypes.join(',')) {
    throw new Error(`expected ${expectedTypes.join(',')}, got ${types.join(',')}`);
  }
}

async function expectIncomplete(chunks: string[]) {
  try {
    await readChatEventStream(readerFor(...chunks), () => {});
  } catch (error) {
    if (String(error).includes('流式连接提前结束')) return;
    throw error;
  }
  throw new Error('Stream without a done event was accepted');
}

await expectStream(
  ['data: {"type":"message_', 'end"}\n\ndata: {"type":"done"}\n\n'],
  ['message_end', 'done'],
);
await expectStream(['data: {"type":"done"}'], ['done']);
await expectIncomplete(['data: {"type":"message_end"}\n\n']);
await expectIncomplete(['data: {"type":"delta","delta":"partial"}\n\n']);
await expectIncomplete([]);
