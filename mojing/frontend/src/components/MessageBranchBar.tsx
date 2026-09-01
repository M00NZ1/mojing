import { storyLineDisplayLabel } from '../utils/storyLinePresentation';

export type BranchAnchor = {
  branch_id: string;
  label: string;
};

type Props = {
  anchors: BranchAnchor[];
  activeBranchId: string;
  canReturnToMain: boolean;
  onSwitchBranch: (branchId: string) => void;
};

export default function MessageBranchBar({
  anchors,
  activeBranchId,
  canReturnToMain,
  onSwitchBranch,
}: Props) {
  if (!canReturnToMain && anchors.length === 0) return null;
  return (
    <div className="message-branch-bar">
      {canReturnToMain && (
        <button
          type="button"
          className={`message-branch-chip ${activeBranchId === 'main' ? 'active' : ''}`}
          onClick={() => onSwitchBranch('main')}
          aria-current={activeBranchId === 'main' ? 'true' : undefined}
        >
          主线剧情
        </button>
      )}
      {anchors.map((a) => {
        const displayLabel = storyLineDisplayLabel(a.branch_id, a.label);
        return (
          <button
            key={a.branch_id}
            type="button"
            className={`message-branch-chip ${activeBranchId === a.branch_id ? 'active' : ''}`}
            onClick={() => onSwitchBranch(a.branch_id)}
            title={`切换到${displayLabel}`}
            aria-current={activeBranchId === a.branch_id ? 'true' : undefined}
          >
            {displayLabel}
          </button>
        );
      })}
    </div>
  );
}
