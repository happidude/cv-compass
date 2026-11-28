export const multiFilters = [
  { name: "function", kind: "FUNCTION", label: "Nhóm nghề" },
  { name: "seniority", kind: "SENIORITY", label: "Cấp bậc" },
  { name: "scope", kind: "SCOPE", label: "Phạm vi" },
  { name: "industry", kind: "INDUSTRY", label: "Ngành" },
  { name: "province", kind: "provinces", label: "Tỉnh/thành" },
] as const;
export const textFilters = ["q", "company", "companyId", "title"] as const;
/** Retired experience range filters: old links may still carry them. */
export const retiredFilters = ["minYears", "maxYears"] as const;
/** Every URL parameter owned by the search form, display-only name included. */
export const searchParamNames = [
  ...textFilters,
  "companyName",
  ...multiFilters.map((filter) => filter.name),
] as const;

export function isCompanyId(value: string) {
  return (
    /^\d+$/.test(value) &&
    BigInt(value) > 0n &&
    BigInt(value) <= 9223372036854775807n
  );
}

// Whitelist only filters: list pagination, stale cursors and retired filters
// never enter search.
export function searchFilters(params: URLSearchParams) {
  const filters = new URLSearchParams();
  const validCompanyId = isCompanyId(params.get("companyId") ?? "");
  for (const name of textFilters) {
    // A shared URL may contain both; only a valid catalog ID takes precedence.
    if (name === "company" && validCompanyId) continue;
    if (name === "companyId" && !validCompanyId) continue;
    const value = params.get(name);
    if (value?.trim()) filters.set(name, value);
  }
  for (const { name } of multiFilters) {
    for (const value of params.getAll(name)) {
      if (value.trim()) filters.append(name, value);
    }
  }
  return filters;
}

/**
 * Search used to live on /candidates. Returns the /search URL an old filter
 * link should be replaced with, or undefined for a plain list URL.
 */
export function legacySearchRedirect(params: URLSearchParams) {
  const names = [...searchParamNames, ...retiredFilters];
  if (!names.some((name) => params.has(name))) return undefined;
  const next = new URLSearchParams();
  for (const name of searchParamNames)
    for (const value of params.getAll(name)) next.append(name, value);
  const query = next.toString();
  return query ? `/search?${query}` : "/search";
}
