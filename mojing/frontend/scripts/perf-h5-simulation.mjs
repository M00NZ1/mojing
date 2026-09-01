/**
 * H5 / PC 浏览器侧「大列表 + 大 JSON」推演（不依赖后端）。
 * 用法：在 frontend 目录执行  npm run perf:h5
 *
 * 对照：useSessionMessages 会把多页 merge 成 flatMessages（全量在 JS 堆中）；
 * 若每页含大正文，总堆占用与每次 immutable 更新成本会上去。
 *
 * 注意：Node 对「单 string」长度有上限，JSON.stringify 超大数组会抛 RangeError——
 * 这本身也说明：若 H5 把巨量消息拼成单 JSON/单字符串，会逼近引擎极限。
 */

const PAGE_SIZE = 80;
const PAGE_COUNT = 400;

function makeMessage(id, contentLen) {
  const pad = "x".repeat(contentLen);
  return {
    id,
    session_id: 1,
    speaker_type: "user",
    content: `m${id}:${pad}`,
    created_at: new Date().toISOString(),
  };
}

function utf8MiBApprox(obj) {
  const s = JSON.stringify(obj);
  return Buffer.byteLength(s, "utf8") / 1024 / 1024;
}

function runScenario(name, contentLen) {
  const pages = [];
  let id = 1;
  const t0 = performance.now();
  for (let p = 0; p < PAGE_COUNT; p++) {
    const items = [];
    for (let i = 0; i < PAGE_SIZE; i++) {
      items.push(makeMessage(id++, contentLen));
    }
    pages.push(items);
  }
  const t1 = performance.now();

  const t2 = performance.now();
  const flat = pages.flat();
  const t3 = performance.now();

  let stringifyMs = null;
  let mib = null;
  try {
    const t4 = performance.now();
    mib = utf8MiBApprox(flat);
    const t5 = performance.now();
    stringifyMs = t5 - t4;
  } catch (e) {
    stringifyMs = -1;
    mib = -1;
    console.log(`  JSON.stringify 失败: ${e?.message ?? e}`);
  }

  console.log(`\n[${name}] contentLen=${contentLen}`);
  console.log(`  build pages: ${(t1 - t0).toFixed(1)} ms, flat(): ${(t3 - t2).toFixed(1)} ms`);
  if (stringifyMs >= 0) {
    console.log(`  JSON.stringify: ${stringifyMs.toFixed(1)} ms, ~${mib.toFixed(2)} MiB UTF-8`);
  } else {
    const approxChars = flat.length * (String(flat[0]?.content?.length ?? 0) + 40);
    console.log(`  粗估总字符 ~${(approxChars / 1e6).toFixed(2)}M（未成功 stringify）`);
  }
}

console.log("H5 perf simulation (Node). 与真 Chrome/React 调度仍有差异，可看数量级。\n");

runScenario("轻量正文（侧栏列表/短消息）", 80);
runScenario("中等正文（长角色回复）", 4000);
// 刻意减轻：避免 Node RangeError，仍比中等场景更重一档
runScenario("偏重（仍小于「数百万 token 全进内存」）", 12000);
