// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { encyclopediaEntryPath, normalizeSettingConflictResult } from './settingConflictPresentation.ts';

function expectEqual<T>(label: string, actual: T, expected: T) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${String(expected)}, received ${String(actual)}`);
  }
}

function expectJsonEqual(label: string, actual: unknown, expected: unknown) {
  expectEqual(label, JSON.stringify(actual), JSON.stringify(expected));
}

{
  const result = normalizeSettingConflictResult({
    conflicts: [{
      message: ' 标题「雾港」重复 ',
      entry_ids: [7, 8],
      entries: [
        { id: 7, title: '雾港', entry_type: 'location', summary: '现行设定' },
        { id: 7, title: '重复引用', entry_type: 'location' },
        { id: 8, title: '雾港旧稿', entry_type: 'location' },
        { id: 0, title: '无效', entry_type: 'location' },
      ],
    }],
  });

  expectEqual('trimmed conflict copy', result.conflicts[0]?.message, '标题「雾港」重复');
  expectJsonEqual('unique valid destinations', result.conflicts[0]?.entries.map((entry) => entry.title), ['雾港', '雾港旧稿']);
  expectEqual('readable duplicate detail', result.conflicts[0]?.entries[0]?.summary, '现行设定');
  expectEqual(
    'entry route',
    encyclopediaEntryPath(3, result.conflicts[0]!.entries[1]!),
    '/encyclopedia?encId=3&category=location&entryId=8',
  );
}

{
  const result = normalizeSettingConflictResult({ conflicts: [{ entry_ids: [21, 22] }] });

  expectEqual('readable fallback', result.conflicts[0]?.message, '发现一项需要确认的设定');
  expectJsonEqual('legacy numeric ids stay hidden', result.conflicts[0]?.entries, []);
}
