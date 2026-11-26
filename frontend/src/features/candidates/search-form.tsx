import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Button, ErrorNotice, Panel } from "../../shared/ui";
import { useCompanySuggestions, useSearchOptions } from "./queries";
import {
  experienceFilterError,
  isCompanyId,
  multiFilters,
  textFilters,
} from "./search-filters";
import { estimatedExperienceHint } from "./search-results";

function readTextFilters(params: URLSearchParams) {
  const companyId = params.get("companyId") ?? "";
  return {
    q: params.get("q") ?? "",
    company: params.has("companyId") ? "" : (params.get("company") ?? ""),
    companyId,
    companyName: isCompanyId(companyId)
      ? (params.get("companyName") ?? "")
      : "",
    title: params.get("title") ?? "",
    minYears: params.get("minYears") ?? "",
    maxYears: params.get("maxYears") ?? "",
  };
}
type TextFilters = ReturnType<typeof readTextFilters>;
function withTextFilters(params: URLSearchParams, draft: TextFilters) {
  const next = new URLSearchParams(params);
  for (const name of [...textFilters, "companyName"] as const) {
    next.delete(name);
    if (draft[name] !== "") next.set(name, draft[name]);
  }
  next.delete("cursor");
  return next;
}

export function CandidateSearchForm() {
  const [params, setParams] = useSearchParams();
  const options = useSearchOptions();
  const [draft, setDraft] = useState(() => readTextFilters(params));
  const url = params.toString();
  const [previousUrl, setPreviousUrl] = useState(url);
  // Restore drafts on back/forward and shared links without remounting inputs.
  if (previousUrl !== url) {
    setPreviousUrl(url);
    setDraft(readTextFilters(params));
  }
  const companySelected = isCompanyId(draft.companyId) && !!draft.companyName;
  const suggestionText = companySelected ? "" : draft.company;
  const [debouncedCompany, setDebouncedCompany] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedCompany(suggestionText), 300);
    return () => clearTimeout(timer);
  }, [suggestionText]);
  useEffect(() => {
    if (
      ([...textFilters, "companyName"] as const).every(
        (name) => draft[name] === (params.get(name) ?? ""),
      )
    )
      return;
    const timer = setTimeout(() => {
      setParams((current) => withTextFilters(current, draft), {
        replace: true,
        flushSync: true,
      });
    }, 300);
    return () => clearTimeout(timer);
  }, [draft, params, setParams]);
  const suggestions = useCompanySuggestions(debouncedCompany);
  const draftParams = new URLSearchParams(draft);
  const error = experienceFilterError(draftParams);

  function handleTextChange(name: (typeof textFilters)[number], value: string) {
    setDraft((current) => ({
      ...current,
      [name]: value,
      ...(name === "company" ? { companyId: "", companyName: "" } : {}),
    }));
  }
  function handleCompanySelection(id = "", name = "") {
    const nextDraft = {
      ...draft,
      company: "",
      companyId: id,
      companyName: name,
    };
    setDraft(nextDraft);
    setParams((current) => withTextFilters(current, nextDraft), {
      flushSync: true,
    });
  }

  function handleChange(name: string, values: string[]) {
    // Include pending text drafts when a discrete selection commits the URL.
    setParams(
      (current) => {
        const next = withTextFilters(current, draft);
        next.delete(name);
        next.delete("cursor");
        for (const value of values) if (value !== "") next.append(name, value);
        return next;
      },
      { flushSync: true },
    );
  }
  function handleClear() {
    setDraft(readTextFilters(new URLSearchParams()));
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        for (const name of [
          ...textFilters,
          "companyName",
          ...multiFilters.map((filter) => filter.name),
          "cursor",
        ])
          next.delete(name);
        return next;
      },
      { flushSync: true },
    );
  }

  return (
    <Panel title="Tìm kiếm ứng viên" padded className="candidate-search">
      <div className="candidate-search__fields">
        <label className="field">
          Từ khóa
          <input
            className="input"
            name="q"
            value={draft.q}
            onChange={(event) => handleTextChange("q", event.target.value)}
          />
        </label>
        <div className="field">
          <label htmlFor="candidate-company">Công ty</label>
          <input
            id="candidate-company"
            className="input"
            name="company"
            autoComplete="off"
            value={companySelected ? draft.companyName : draft.company}
            placeholder="Gõ tên hoặc chọn gợi ý"
            aria-describedby={companySelected ? "selected-company" : undefined}
            onChange={(event) =>
              handleTextChange("company", event.target.value)
            }
          />
          {companySelected && (
            <>
              <span id="selected-company" className="field__hint">
                Đã chọn công ty từ danh mục.
              </span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleCompanySelection()}
              >
                Bỏ chọn công ty
              </Button>
            </>
          )}
          {suggestionText && suggestionText === debouncedCompany && (
            <>
              {suggestions.isFetching && (
                <span role="status">Đang tải gợi ý…</span>
              )}
              {suggestions.error && (
                <ErrorNotice
                  error={suggestions.error}
                  retry={() => void suggestions.refetch()}
                />
              )}
              {!!suggestions.data?.length && (
                <ul
                  className="candidate-search__suggestions"
                  aria-label="Gợi ý công ty"
                >
                  {suggestions.data.map((item) => (
                    <li key={item.id}>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => {
                          handleCompanySelection(item.id, item.name);
                        }}
                      >
                        {item.name}
                        {item.parentName ? ` (${item.parentName})` : ""}
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>
        <label className="field">
          Chức danh
          <input
            className="input"
            name="title"
            value={draft.title}
            onChange={(event) => handleTextChange("title", event.target.value)}
          />
        </label>
      </div>
      {options.error && (
        <ErrorNotice
          error={options.error}
          retry={() => void options.refetch()}
        />
      )}
      <div className="candidate-search__groups">
        {multiFilters.map(({ name, kind, label }) => {
          const entries = options.data?.[kind];
          const choices =
            entries?.flatMap((entry) => {
              if (typeof entry === "string")
                return [{ code: entry, label: entry }];
              return entry.label.trim() ? [entry] : [];
            }) ?? [];
          const values = params.getAll(name);
          return (
            <fieldset key={name} className="candidate-search__group">
              <legend>{label}</legend>
              {options.error ? (
                <span>Không tải được lựa chọn.</span>
              ) : (
                !options.data && <span>Đang tải…</span>
              )}
              {options.data &&
                values.some(
                  (value) => !choices.some((choice) => choice.code === value),
                ) && (
                  <span className="field__hint">
                    Một lựa chọn chưa có nhãn. Bạn có thể xóa lọc để đặt lại.
                  </span>
                )}
              <div className="candidate-search__choices">
                {choices.map((choice) => (
                  <label key={choice.code} className="checkbox">
                    <input
                      type="checkbox"
                      name={name}
                      value={choice.code}
                      checked={values.includes(choice.code)}
                      onChange={(event) =>
                        handleChange(
                          name,
                          event.target.checked
                            ? [...values, choice.code]
                            : values.filter((value) => value !== choice.code),
                        )
                      }
                    />
                    {choice.label}
                  </label>
                ))}
              </div>
            </fieldset>
          );
        })}
        <fieldset
          className="candidate-search__group"
          aria-describedby="experience-filter-hint"
        >
          <legend>Kinh nghiệm (dự tính)</legend>
          <div className="form-grid">
            {(
              [
                ["minYears", "Số năm tối thiểu"],
                ["maxYears", "Số năm tối đa"],
              ] as const
            ).map(([name, label]) => (
              <label key={name} className="field">
                {label}
                <input
                  className="input"
                  name={name}
                  type="text"
                  inputMode="numeric"
                  value={draft[name]}
                  aria-invalid={!!error}
                  aria-describedby={
                    error ? "experience-filter-error" : "experience-filter-hint"
                  }
                  onChange={(event) =>
                    handleTextChange(name, event.target.value)
                  }
                />
              </label>
            ))}
          </div>
          <p id="experience-filter-hint" className="field__hint">
            {estimatedExperienceHint}
          </p>
        </fieldset>
      </div>
      {error && (
        <p
          id="experience-filter-error"
          className="notice notice--bad"
          role="alert"
        >
          {error}
        </p>
      )}
      <div className="btn-row">
        <Button variant="secondary" onClick={handleClear}>
          Xóa lọc
        </Button>
      </div>
    </Panel>
  );
}
