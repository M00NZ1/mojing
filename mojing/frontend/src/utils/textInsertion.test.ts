// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { insertTextAtSelection } from './textInsertion.ts';

const cases = [
  {
    label: 'inserts at the beginning',
    result: insertTextAtSelection('正文', 0, 0, '快捷词'),
    expected: { text: '快捷词正文', cursor: 3 },
  },
  {
    label: 'inserts in the middle',
    result: insertTextAtSelection('前后', 1, 1, '快捷词'),
    expected: { text: '前快捷词后', cursor: 4 },
  },
  {
    label: 'replaces the selected range',
    result: insertTextAtSelection('前旧内容后', 1, 4, '快捷词'),
    expected: { text: '前快捷词后', cursor: 4 },
  },
  {
    label: 'clamps an invalid range',
    result: insertTextAtSelection('原文', -4, 99, '快捷词'),
    expected: { text: '快捷词', cursor: 3 },
  },
  {
    label: 'uses browser UTF-16 cursor offsets for emoji',
    result: insertTextAtSelection('前旧后', 1, 2, '❤️'),
    expected: { text: '前❤️后', cursor: 3 },
  },
];

for (const testCase of cases) {
  if (
    testCase.result.text !== testCase.expected.text
    || testCase.result.cursor !== testCase.expected.cursor
  ) {
    throw new Error(`${testCase.label}: ${JSON.stringify(testCase.result)}`);
  }
}
