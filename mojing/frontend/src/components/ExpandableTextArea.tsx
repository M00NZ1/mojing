import { useId, useState, type TextareaHTMLAttributes } from 'react';
import './ExpandableTextArea.css';

type Props = Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'rows'>;

export default function ExpandableTextArea({ id, className = '', disabled, ...props }: Props) {
  const generatedId = useId();
  const fieldId = id || generatedId;
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="expandable-textarea">
      <textarea {...props} id={fieldId} className={className} disabled={disabled} rows={expanded ? 16 : 6} />
      <button
        type="button"
        className="btn btn-sm expandable-textarea-toggle"
        aria-expanded={expanded}
        aria-controls={fieldId}
        disabled={disabled}
        onClick={() => setExpanded(value => !value)}
      >
        {expanded ? '收起编辑' : '展开编辑'}
      </button>
    </div>
  );
}
