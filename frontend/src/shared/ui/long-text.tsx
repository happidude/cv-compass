/** Raw extracted text. Always rendered as text, never as HTML. */
export function LongText({ text }: { text: string }) {
  const length = [...text].length;
  return length > 1200 ? (
    <details className="disclosure">
      <summary>
        Hiện thêm nội dung ({length.toLocaleString("vi-VN")} ký tự)
      </summary>
      <pre className="text-block">{text}</pre>
    </details>
  ) : (
    <pre className="text-block">{text}</pre>
  );
}
