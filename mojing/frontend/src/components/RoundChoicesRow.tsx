interface Props {
  choices: string[];
  onSelect: (choice: string) => void;
}

export default function RoundChoicesRow({ choices, onSelect }: Props) {
  if (choices.length === 0) return null;
  return (
    <div className="round-choices-row" role="group" aria-label="本回合可选行动">
      <span className="round-choices-label">本回合可选 · 选择后可继续编辑</span>
      <div className="round-choices-chips">
        {choices.map((choice) => (
          <button
            key={choice}
            type="button"
            className="round-choice-chip"
            onClick={() => onSelect(choice)}
          >
            {choice}
          </button>
        ))}
      </div>
    </div>
  );
}
