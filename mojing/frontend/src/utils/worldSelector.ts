import type { WorldEncyclopedia, WorldTemplate } from '../types';

export type WorldSelectorItem = {
  value: string;
  label: string;
  encyclopediaId: number | null;
  templateId: string;
  legacy: boolean;
};

export function buildWorldSelectorItems(
  templates: WorldTemplate[] = [],
  encyclopedias: Array<Pick<WorldEncyclopedia, 'id' | 'name' | 'entry_count'>> = [],
  currentTemplateId = 'custom',
  currentEncyclopediaId: number | null = null,
): WorldSelectorItem[] {
  currentTemplateId ||= 'custom';
  const mappedTemplateByEncyclopedia = new Map<number, WorldTemplate>();
  templates.forEach((template) => {
    if (template.encyclopedia_id != null && !mappedTemplateByEncyclopedia.has(template.encyclopedia_id)) {
      mappedTemplateByEncyclopedia.set(template.encyclopedia_id, template);
    }
  });
  const canonical = encyclopedias.map((encyclopedia) => {
    const template = mappedTemplateByEncyclopedia.get(encyclopedia.id);
    return {
      value: `world:${encyclopedia.id}`,
      label: `${encyclopedia.name}${encyclopedia.entry_count == null ? '' : ` · ${encyclopedia.entry_count} 条`}`,
      encyclopediaId: encyclopedia.id,
      templateId: template?.template_id ?? 'custom',
      legacy: false,
    };
  });
  const legacy = templates
    .filter((template) => template.encyclopedia_id == null && template.template_id !== 'custom')
    .map((template) => ({
      value: `template:${template.template_id}`,
      label: `${template.label}（旧模板）`,
      encyclopediaId: null,
      templateId: template.template_id,
      legacy: true,
    }));
  const exactCurrent = [...canonical, ...legacy].some((item) => (
    item.templateId === currentTemplateId && item.encyclopediaId === currentEncyclopediaId
  ));
  if (!exactCurrent && (currentTemplateId !== 'custom' || currentEncyclopediaId != null)) {
    return [{
      value: `current:${currentTemplateId}:${currentEncyclopediaId ?? ''}`,
      label: '当前组合（兼容）',
      encyclopediaId: currentEncyclopediaId,
      templateId: currentTemplateId,
      legacy: true,
    }, ...canonical, ...legacy];
  }
  return [...canonical, ...legacy];
}

export function worldSelectorValue(templateId: string, encyclopediaId: number | null, items: WorldSelectorItem[]): string {
  templateId ||= 'custom';
  const exact = items.find((item) => item.templateId === templateId && item.encyclopediaId === encyclopediaId);
  if (exact) return exact.value;
  if (encyclopediaId != null) return `world:${encyclopediaId}`;
  if (templateId && templateId !== 'custom') return `template:${templateId}`;
  return '';
}

export function resolveWorldSelectorValue(value: string, items: WorldSelectorItem[]) {
  const selected = items.find((item) => item.value === value);
  return selected ?? { encyclopediaId: null, templateId: 'custom', legacy: false };
}
