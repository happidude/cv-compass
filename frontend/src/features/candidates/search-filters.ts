export const multiFilters = [
  { name: "function", kind: "FUNCTION", label: "Nhóm nghề" },
  { name: "seniority", kind: "SENIORITY", label: "Cấp bậc" },
  { name: "scope", kind: "SCOPE", label: "Phạm vi" },
  { name: "industry", kind: "INDUSTRY", label: "Ngành" },
  { name: "province", kind: "provinces", label: "Tỉnh/thành" },
] as const;
export const textFilters = [
  "q",
  "company",
  "title",
  "minYears",
  "maxYears",
] as const;

// A numeric free-text name cannot be distinguished from an ID by this API.
// Only the UI selection marker lets us safely submit such a company filter.
export function companyFilterNeedsSelection(params: URLSearchParams) {
  return (
    !params.get("companyName") &&
    /^\d+$/.test(params.get("company")?.trim() ?? "")
  );
}

// Whitelist only filters: list pagination and stale cursors never enter search.
export function searchFilters(params: URLSearchParams) {
  const filters = new URLSearchParams();
  for (const name of textFilters) {
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

export function experienceFilterError(params: URLSearchParams) {
  const min = params.get("minYears") ?? "";
  const max = params.get("maxYears") ?? "";
  if (
    [min, max].some(
      (value) =>
        value !== "" &&
        (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value))),
    )
  )
    return "Số năm kinh nghiệm phải là số nguyên lớn hơn hoặc bằng 0.";
  if (min !== "" && max !== "" && Number(min) > Number(max))
    return "Số năm tối thiểu phải nhỏ hơn hoặc bằng số năm tối đa.";
  return undefined;
}
