// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { canSaveEditedMessage, hasUnsavedEditMessage } from './editMessageDraft.ts';

function expectEqual<T>(label: string, actual: T, expected: T) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${String(expected)}, received ${String(actual)}`);
  }
}

expectEqual('unchanged draft', hasUnsavedEditMessage('原消息', '原消息'), false);
expectEqual('changed draft', hasUnsavedEditMessage('新消息', '原消息'), true);
expectEqual('whitespace is still unsaved input', hasUnsavedEditMessage('原消息 ', '原消息'), true);

expectEqual('changed non-empty content saves', canSaveEditedMessage('新消息', '原消息', false), true);
expectEqual('empty content does not save', canSaveEditedMessage('   ', '原消息', false), false);
expectEqual('trim-equivalent content does not save', canSaveEditedMessage(' 原消息 ', '原消息', false), false);
expectEqual('saving locks another submission', canSaveEditedMessage('新消息', '原消息', true), false);
