// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { mergeSelectedFiles, removeSelectedFiles, splitFilesBySize } from './fileSelection.ts';

type TestFile = {
  name: string;
  size: number;
  type: string;
  lastModified: number;
};

const first: TestFile = { name: 'first.png', size: 120, type: 'image/png', lastModified: 1 };
const second: TestFile = { name: 'second.jpg', size: 240, type: 'image/jpeg', lastModified: 2 };
const sameNameButUpdated: TestFile = { ...first, size: 121, lastModified: 3 };

const appended = mergeSelectedFiles([first], [second]);
if (appended.length !== 2 || appended[0] !== first || appended[1] !== second) {
  throw new Error('a later file selection must append without replacing the existing selection');
}

const deduplicated = mergeSelectedFiles(appended, [{ ...first }, second]);
if (deduplicated.length !== 2) {
  throw new Error('re-selecting the exact same files must not create duplicate attachments');
}

const preservedDifferentFile = mergeSelectedFiles(deduplicated, [sameNameButUpdated]);
if (preservedDifferentFile.length !== 3 || preservedDifferentFile[2] !== sameNameButUpdated) {
  throw new Error('files with the same name but different metadata must remain selectable');
}

if (mergeSelectedFiles([], []).length !== 0) {
  throw new Error('an empty selection must remain empty');
}

const nextMessageFile: TestFile = { name: 'next.png', size: 360, type: 'image/png', lastModified: 4 };
const afterCompletedSend = removeSelectedFiles([first, nextMessageFile], [first]);
if (afterCompletedSend.length !== 1 || afterCompletedSend[0] !== nextMessageFile) {
  throw new Error('a completed send must remove its own files and preserve files selected for the next message');
}

const reselectedSameMetadata: TestFile = { ...first };
const afterReselection = removeSelectedFiles([reselectedSameMetadata], [first]);
if (afterReselection.length !== 1 || afterReselection[0] !== reselectedSameMetadata) {
  throw new Error('a newly selected File object must survive even when its metadata matches a sent file');
}

const unchanged = [nextMessageFile];
if (removeSelectedFiles(unchanged, [first]) !== unchanged) {
  throw new Error('unrelated completion must preserve the existing array identity');
}

const sized = splitFilesBySize([first, second], 120);
if (sized.accepted.length !== 1 || sized.accepted[0] !== first || sized.rejected[0] !== second) {
  throw new Error('the file-size boundary must accept an exact-limit file and reject only larger files');
}
