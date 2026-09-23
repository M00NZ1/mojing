// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { clearPendingChatSend, loadPendingChatSend, savePendingChatSend, type PendingChatSend } from './chatDraftStorage.ts';

const values = new Map<string, string>();
Object.defineProperty(globalThis, 'window', {
  configurable: true,
  value: {
    localStorage: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => { values.set(key, value); },
      removeItem: (key: string) => { values.delete(key); },
    },
  },
});

const send: PendingChatSend = {
  clientMessageId: 'fd6429e9-0fa5-4a72-b692-0c2dcd903ed3',
  sessionId: 7,
  branchId: 'story-a',
  content: '你说过这句话吗？',
  input: '你说过这句话吗？',
  quote: null,
};

if (!savePendingChatSend(send)) throw new Error('Failed to retain pending send');
if (loadPendingChatSend(7)?.clientMessageId !== send.clientMessageId) throw new Error('Pending send did not survive reload');
if (loadPendingChatSend(8) !== null) throw new Error('Pending send leaked to another session');
clearPendingChatSend(7, '32f371d5-5144-4e8d-9f98-8d64d5f15fd2');
if (loadPendingChatSend(7) === null) throw new Error('Different send cleared pending recovery');
clearPendingChatSend(7, send.clientMessageId);
if (loadPendingChatSend(7) !== null) throw new Error('Confirmed send still appears pending');

const attachmentSend: PendingChatSend = {
  ...send, clientMessageId: 'bf8baaaf-51f4-441b-9753-e8459ead188e', content: '', input: '',
  files: [{ name: 'scene.png', size: 32, type: 'image/png' }],
};
if (!savePendingChatSend(attachmentSend) || loadPendingChatSend(7)?.files?.[0].name !== 'scene.png') {
  throw new Error('Pending attachment send did not survive reload');
}
