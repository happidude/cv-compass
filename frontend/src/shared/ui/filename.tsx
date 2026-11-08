import { displayName } from "../format";
/** User-supplied file name, neutralised for control/bidi characters. */
export function Filename({ name }: { name: string }) {
  const safe = displayName(name);
  return (
    <bdi className="filename" title={safe}>
      {safe}
    </bdi>
  );
}
