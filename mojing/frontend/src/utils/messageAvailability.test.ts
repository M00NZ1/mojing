// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { canCreateEntryFromMessage } from './messageAvailability.ts';

const persistedMessage = { id: 42 };
const streamingMessage = { id: -42 };

if (canCreateEntryFromMessage([], false)) {
  throw new Error('空会话不应允许沉淀百科条目');
}

if (canCreateEntryFromMessage([streamingMessage], true)) {
  throw new Error('消息生成期间不应允许沉淀百科条目');
}

if (canCreateEntryFromMessage([persistedMessage], false) !== true) {
  throw new Error('消息落库后应恢复沉淀百科条目的可用性');
}

if (canCreateEntryFromMessage([persistedMessage, streamingMessage], false)) {
  throw new Error('最后一条仍为临时消息时不应把临时 ID 交给动作');
}
