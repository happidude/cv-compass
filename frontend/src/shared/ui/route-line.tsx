import { cn } from "../utils";
/**
 * Signature component: a record's processing path drawn as a route with
 * stations. The full variant labels every stop in text. The compact variant
 * is a visual summary only (hidden from assistive tech), so it must always
 * sit next to a Status badge or text that states the same thing.
 * Use state "unknown" when the API does not report a stage; never guess.
 */
export type RouteState =
  | "done"
  | "active"
  | "waiting"
  | "attention"
  | "failed"
  | "todo"
  | "unknown";
export type RouteStop = { label: string; state: RouteState; detail: string };
export function RouteLine({
  stops,
  compact = false,
  vertical = false,
  className,
  label,
}: {
  stops: RouteStop[];
  compact?: boolean;
  vertical?: boolean;
  className?: string;
  label: string;
}) {
  return (
    <ol
      className={cn(
        "route",
        compact && "route--compact",
        vertical && "route--vertical",
        className,
      )}
      aria-label={compact ? undefined : label}
      aria-hidden={compact ? "true" : undefined}
      title={
        compact
          ? stops.map((s) => `${s.label}: ${s.detail}`).join(" → ")
          : undefined
      }
    >
      {stops.map((stop, i) => (
        <li
          key={stop.label}
          className="route__stop"
          data-state={stop.state}
          data-next={stops[i + 1]?.state === "unknown" ? "unknown" : undefined}
        >
          <span className="route__track" aria-hidden="true">
            <span className="route__dot" />
          </span>
          {!compact && (
            <span className="route__label">
              <strong>{stop.label}</strong>
              {stop.detail}
            </span>
          )}
        </li>
      ))}
    </ol>
  );
}
