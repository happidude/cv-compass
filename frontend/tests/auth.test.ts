import { it, expect } from "vitest";
import { returnPath } from "../src/app/auth";
const from = (pathname: unknown, search: unknown = "") => ({
  from: { pathname, search },
});
it("returns to the in-app page that required login, query included", () => {
  expect(returnPath(from("/candidates/1", "?x=1"))).toBe("/candidates/1?x=1");
  expect(returnPath(from("/documents"))).toBe("/documents");
});
it("falls back to the overview for unsafe or missing destinations", () => {
  for (const state of [
    from("//evil.example"),
    from("/\\evil.example"),
    from("https://evil.example"),
    from("/login", "?next=1"),
    from(42),
    { from: null },
    null,
    undefined,
    "junk",
  ])
    expect(returnPath(state)).toBe("/");
});
