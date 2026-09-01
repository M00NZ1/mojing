import { useMemo } from 'react';
import type { SessionBranch } from '../types';
import { storyLineDisplayLabel } from '../utils/storyLinePresentation';

type Props = {
  items: SessionBranch[];
  activeBranchId: string;
  onSelect: (branchId: string) => void;
  onContinue: (branchId: string) => void;
};

type TreeNode = {
  branch: SessionBranch;
  children: TreeNode[];
  depth: number;
  x: number;
  y: number;
};

const NODE_W = 200;
const NODE_H = 124;
const H_GAP = 40;
const V_GAP = 30;

function StoryLineCard({
  node,
  isActive,
  onSelect,
  onContinue,
}: {
  node: TreeNode;
  isActive: boolean;
  onSelect: (branchId: string) => void;
  onContinue: (branchId: string) => void;
}) {
  const displayLabel = storyLineDisplayLabel(node.branch.branch_id, node.branch.label);
  return (
    <div className={`mini-card branch-tree-card ${isActive ? 'active' : ''}`}>
      <div className="branch-tree-summary">
        <span className="branch-tree-node-title">
          <span>{displayLabel}</span>
          {isActive && <span className="branch-tree-current">当前</span>}
        </span>
        <span className="branch-tree-node-meta">{node.branch.message_count} 条消息</span>
        <span className="branch-tree-node-preview">
          {node.branch.source_message_preview || (node.branch.branch_id === 'main' ? '故事起点' : '起点信息不可用')}
        </span>
      </div>
      <div className="branch-tree-actions">
        <button type="button"
          className="ghost-button inline-button branch-tree-action"
          disabled={isActive}
          aria-current={isActive ? 'true' : undefined}
          onClick={() => onSelect(node.branch.branch_id)}
        >
          {isActive ? '当前' : '打开'}
        </button>
        <button type="button"
          className="ghost-button inline-button branch-tree-action"
          title="从这条故事线末尾继续生成"
          onClick={() => onContinue(node.branch.branch_id)}
        >
          从此续写
        </button>
      </div>
    </div>
  );
}

export default function BranchTreeGraph({ items, activeBranchId, onSelect, onContinue }: Props) {
  const branchMap = useMemo(() => {
    const map = new Map<string, SessionBranch>();
    for (const item of items) {
      map.set(item.branch_id, item);
    }
    if (!map.has('main')) {
      map.set('main', {
        branch_id: 'main',
        label: '主线剧情',
        parent_branch_id: null,
        source_message_id: null,
        source_message_preview: null,
        source_created_at: null,
        latest_created_at: null,
        depth: 0,
        message_count: 0,
        latest_message_id: null,
      });
    }
    return map;
  }, [items]);

  const tree = useMemo(() => {
    const childrenMap = new Map<string, SessionBranch[]>();
    for (const [, branch] of branchMap) {
      if (branch.branch_id === 'main') continue;
      const parentId = branch.parent_branch_id || 'main';
      const bucket = childrenMap.get(parentId) ?? [];
      bucket.push(branch);
      childrenMap.set(parentId, bucket);
    }
    for (const [, value] of childrenMap) {
      value.sort((a, b) => (a.source_message_id ?? 0) - (b.source_message_id ?? 0));
    }

    function buildNode(branchId: string, depth: number): TreeNode | null {
      const branch = branchMap.get(branchId);
      if (!branch) return null;
      const children: TreeNode[] = [];
      const rawChildren = childrenMap.get(branchId) ?? [];
      for (const child of rawChildren) {
        const childNode = buildNode(child.branch_id, depth + 1);
        if (childNode) children.push(childNode);
      }
      return { branch, children, depth, x: 0, y: 0 };
    }

    return buildNode('main', 0);
  }, [branchMap]);

  const layout = useMemo(() => {
    if (!tree) return { nodes: [], edges: [] as Array<[number, number]>, svgW: 0, svgH: 0 };

    const nodes: Array<{ node: TreeNode; x: number; y: number }> = [];
    const edges: Array<[number, number]> = [];
    const subtreeWidths = new Map<TreeNode, number>();

    function measureSubtree(tn: TreeNode): number {
      const childrenWidth = tn.children.reduce(
        (width, child, index) => width + measureSubtree(child) + (index > 0 ? H_GAP : 0),
        0,
      );
      const width = Math.max(NODE_W, childrenWidth);
      subtreeWidths.set(tn, width);
      return width;
    }

    function layoutNode(tn: TreeNode, offsetX: number): number {
      const subtreeWidth = subtreeWidths.get(tn) ?? NODE_W;
      const nodeX = offsetX + (subtreeWidth - NODE_W) / 2;
      const nodeY = tn.depth * (NODE_H + V_GAP);
      tn.x = nodeX;
      tn.y = nodeY;
      const index = nodes.length;
      nodes.push({ node: tn, x: nodeX, y: nodeY });

      let childX = offsetX;
      for (const child of tn.children) {
        const childIndex = layoutNode(child, childX);
        edges.push([index, childIndex]);
        childX += (subtreeWidths.get(child) ?? NODE_W) + H_GAP;
      }
      return index;
    }

    const totalWidth = measureSubtree(tree);
    layoutNode(tree, 20);
    const totalHeight = nodes.reduce(
      (height, item) => Math.max(height, item.y + NODE_H + 20),
      0,
    );

    return { nodes, edges, svgW: Math.max(totalWidth + 40, 400), svgH: Math.max(totalHeight, 200) };
  }, [tree]);

  return (
    <div className="branch-tree-graph">
      <div className="branch-tree-compact">
        {layout.nodes.map(({ node }, index) => (
          <div
            key={`compact-node-${index}`}
            className="branch-tree-compact-item"
            style={{ marginInlineStart: Math.min(node.depth * 12, 24) }}
          >
            <StoryLineCard
              node={node}
              isActive={node.branch.branch_id === activeBranchId}
              onSelect={onSelect}
              onContinue={onContinue}
            />
          </div>
        ))}
      </div>
      <svg className="branch-tree-desktop" width={layout.svgW} height={layout.svgH} style={{ minWidth: layout.svgW }}>
        {layout.edges.map(([fromIdx, toIdx], i) => {
          const from = layout.nodes[fromIdx];
          const to = layout.nodes[toIdx];
          if (!from || !to) return null;
          const x1 = from.x + NODE_W / 2;
          const y1 = from.y + NODE_H;
          const x2 = to.x + NODE_W / 2;
          const y2 = to.y;
          const cy = (y1 + y2) / 2;
          return (
            <path
              key={`edge-${i}`}
              d={`M ${x1} ${y1} C ${x1} ${cy}, ${x2} ${cy}, ${x2} ${y2}`}
              fill="none"
              stroke="var(--line)"
              strokeWidth={1.5}
              strokeDasharray="4 3"
              opacity={0.6}
            />
          );
        })}
        {layout.nodes.map(({ node, x, y }, i) => (
          <g key={`node-${i}`}>
            <foreignObject x={x} y={y} width={NODE_W} height={NODE_H}>
              <StoryLineCard
                node={node}
                isActive={node.branch.branch_id === activeBranchId}
                onSelect={onSelect}
                onContinue={onContinue}
              />
            </foreignObject>
          </g>
        ))}
      </svg>
      {layout.nodes.length <= 1 && (
        <div className="guide-inline" style={{ padding: 16, textAlign: 'center' }}>
          还没有其他故事线。打开任意消息的操作菜单，选择「从此创建故事线」即可开始。
        </div>
      )}
    </div>
  );
}
