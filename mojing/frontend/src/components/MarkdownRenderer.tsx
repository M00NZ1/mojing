import ReactMarkdown from 'react-markdown';
import type { Element, Root, Text } from 'hast';
import remarkGfm from 'remark-gfm';

type Props = {
  content: string;
  highlightQuery?: string;
};

function rehypeHighlight(query: string) {
  const escaped = query.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(escaped, 'giu');
  return (tree: Root) => {
    function visit(parent: Root | Element) {
      for (let index = 0; index < parent.children.length; index++) {
        const child = parent.children[index];
        if (child.type === 'element') {
          if (child.tagName !== 'mark') visit(child);
        } else if (child.type === 'text') {
          const pieces: Array<Text | Element> = [];
          let offset = 0;
          for (const match of child.value.matchAll(pattern)) {
            const start = match.index ?? 0;
            if (start > offset) pieces.push({ type: 'text', value: child.value.slice(offset, start) });
            pieces.push({ type: 'element', tagName: 'mark', properties: { className: ['message-search-match'] }, children: [{ type: 'text', value: match[0] }] });
            offset = start + match[0].length;
          }
          if (!pieces.length) continue;
          if (offset < child.value.length) pieces.push({ type: 'text', value: child.value.slice(offset) });
          parent.children.splice(index, 1, ...pieces);
          index += pieces.length - 1;
        }
      }
    }
    visit(tree);
  };
}

export default function MarkdownRenderer({ content, highlightQuery = '' }: Props) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={highlightQuery.trim() ? [[rehypeHighlight, highlightQuery]] : []}
      components={{
        a: ({ href, children }) => (
          <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
        ),
        pre: ({ children }) => <pre className="md-code-block">{children}</pre>,
        code: ({ className, children, ...props }) => {
          const isInline = !className;
          if (isInline) {
            return <code className="md-inline-code">{children}</code>;
          }
          return <code className={className} {...props}>{children}</code>;
        },
        table: ({ children }) => (
          <div className="md-table-wrap">
            <table className="md-table">{children}</table>
          </div>
        ),
        blockquote: ({ children }) => (
          <blockquote className="md-blockquote">{children}</blockquote>
        ),
        ul: ({ children }) => <ul className="md-list">{children}</ul>,
        ol: ({ children }) => <ol className="md-list">{children}</ol>,
        img: ({ src, alt }) => (
          <img src={src} alt={alt || ''} className="md-image" loading="lazy" />
        ),
        h1: ({ children }) => <h1 className="md-heading">{children}</h1>,
        h2: ({ children }) => <h2 className="md-heading">{children}</h2>,
        h3: ({ children }) => <h3 className="md-heading">{children}</h3>,
        p: ({ children }) => <p className="md-paragraph">{children}</p>,
      }}
    >
      {content}
    </ReactMarkdown>
  );
}
