/** Upload outcome of a batch as one bar; every segment is an API count. */
export function CountBar({
  total,
  good,
  bad,
}: {
  total: number;
  good: number;
  bad: number;
}) {
  const pct = (n: number) => (total > 0 ? `${(n / total) * 100}%` : "0%");
  return (
    <span
      className="count-bar"
      role="img"
      aria-label={`${good} thành công, ${bad} thất bại trên ${total} file`}
    >
      <span className="is-good" style={{ width: pct(good) }} />
      <span className="is-bad" style={{ width: pct(bad) }} />
    </span>
  );
}
