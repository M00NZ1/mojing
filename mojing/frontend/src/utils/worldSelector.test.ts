import { buildWorldSelectorItems, resolveWorldSelectorValue, worldSelectorValue } from './worldSelector';

const templates = [
  { id: 1, encyclopedia_id: 8, label: '映射模板', template_id: 'mapped', category: '', summary: '', gameplay_mode: '自由剧情', world_prompt: '', cover_image_path: '', suggested_choices: [], anti_cheat_prompt: '', is_builtin: false },
  { id: 2, label: '旧模板', template_id: 'legacy', category: '', summary: '', gameplay_mode: '自由剧情', world_prompt: '', cover_image_path: '', suggested_choices: [], anti_cheat_prompt: '', is_builtin: false },
];
const encyclopedias = [{ id: 8, name: '八号世界', entry_count: 3 }, { id: 9, name: '九号世界', entry_count: 0 }];

const canonical = buildWorldSelectorItems([...templates], encyclopedias);
if (canonical.map((item) => item.value).join(',') !== 'world:8,world:9,template:legacy') throw new Error('canonical and legacy options mismatch');
const current = buildWorldSelectorItems([...templates], encyclopedias, 'mapped', null);
if (worldSelectorValue('mapped', null, current) !== 'current:mapped:') throw new Error('missing current compatibility option');
const resolved = resolveWorldSelectorValue('current:mapped:', current);
if (resolved.templateId !== 'mapped' || resolved.encyclopediaId !== null) throw new Error('compatibility option lost IDs');
if (buildWorldSelectorItems([...templates], encyclopedias, 'custom', null).some((item) => item.value === 'template:custom')) throw new Error('custom duplicate option');
