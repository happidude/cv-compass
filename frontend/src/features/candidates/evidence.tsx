import { MapPin } from "lucide-react";
/**
 * Evidence reference drawn as a map grid reference ("Tr.3 · Đ.1"). The
 * accessible name stays the full sentence so screen readers and tests read
 * "Đối chiếu … trang 3, đoạn 1".
 */
export function EvidenceButton({
  page,
  index,
  label,
  onClick,
}: {
  page: number;
  index: number;
  label: string;
  onClick: () => void;
}) {
  return (
    <button type="button" className="ref-chip" title={label} onClick={onClick}>
      <MapPin size={12} aria-hidden="true" />
      <span aria-hidden="true">
        Tr.{page} · Đ.{index}
      </span>
      <span className="sr-only">{label}</span>
    </button>
  );
}
