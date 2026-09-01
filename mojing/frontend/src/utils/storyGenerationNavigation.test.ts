// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { shouldBlockStoryGenerationNavigation } from './storyGenerationNavigation.ts';

const current = { pathname: '/story-simulation', search: '', hash: '' };

function expectEqual(label: string, actual: boolean, expected: boolean) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${String(expected)}, received ${String(actual)}`);
  }
}

expectEqual(
  'active generation blocks another page',
  shouldBlockStoryGenerationNavigation(true, false, current, { pathname: '/settings', search: '', hash: '' }),
  true,
);
expectEqual(
  'active generation blocks a query navigation',
  shouldBlockStoryGenerationNavigation(true, false, current, { ...current, search: '?panel=open' }),
  true,
);
expectEqual(
  'same location is not blocked',
  shouldBlockStoryGenerationNavigation(true, false, current, current),
  false,
);
expectEqual(
  'completed generation can enter its session',
  shouldBlockStoryGenerationNavigation(true, true, current, { pathname: '/chat/12', search: '', hash: '' }),
  false,
);
expectEqual(
  'idle page can leave',
  shouldBlockStoryGenerationNavigation(false, false, current, { pathname: '/create', search: '', hash: '' }),
  false,
);
