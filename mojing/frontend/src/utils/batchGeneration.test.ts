// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { BATCH_GENERATE_MAX_COUNT, BATCH_GENERATE_MIN_COUNT, hasBatchGenerateAnchor, parseBatchGenerateCount } from './batchGeneration.ts';

function expectEqual<T>(label: string, actual: T, expected: T) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${String(expected)}, received ${String(actual)}`);
  }
}

expectEqual('minimum count', parseBatchGenerateCount(String(BATCH_GENERATE_MIN_COUNT)), 5);
expectEqual('trimmed count', parseBatchGenerateCount(' 10 '), 10);
expectEqual('maximum count', parseBatchGenerateCount(String(BATCH_GENERATE_MAX_COUNT)), 20);
expectEqual('below range', parseBatchGenerateCount('4'), null);
expectEqual('above range', parseBatchGenerateCount('21'), null);
expectEqual('decimal count', parseBatchGenerateCount('10.5'), null);
expectEqual('non-number count', parseBatchGenerateCount('not-a-number'), null);
expectEqual('empty count', parseBatchGenerateCount(''), null);

expectEqual('complete encyclopedia anchor', hasBatchGenerateAnchor(false, '', false), true);
expectEqual('weak anchor with hint', hasBatchGenerateAnchor(true, '蒸汽朋克海港', false), true);
expectEqual('weak anchor acknowledged', hasBatchGenerateAnchor(true, '   ', true), true);
expectEqual('weak anchor unresolved', hasBatchGenerateAnchor(true, '   ', false), false);
