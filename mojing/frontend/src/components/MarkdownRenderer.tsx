import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

type Props = {
  content: string;
};

export default function MarkdownRenderer({ content }: Props) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
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
