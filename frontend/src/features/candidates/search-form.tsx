import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Button, ErrorNotice, Panel } from "../../shared/ui";
import { useCompanySuggestions, useSearchOptions } from "./queries";
import {
  experienceFilterError,
  multiFilters,
  textFilters,
} from "./search-filters";
import { estimatedExperienceHint } from "./search-results";

export function CandidateSearchForm() {
  const [params, setParams] = useSearchParams();
  const options = useSearchOptions();
  const company = params.get("company") ?? "";
  const [selectedCompany, setSelectedCompany] = useState({ id: "", name: "" });
  const companyIsId = /^\d+$/.test(company);
  const suggestionText = companyIsId ? "" : company;
  const [debouncedCompany, setDebouncedCompany] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedCompany(suggestionText), 300);
    return () => clearTimeout(timer);
  }, [suggestionText]);
  const suggestions = useCompanySuggestions(debouncedCompany);
  const error = experienceFilterError(params);

  function handleChange(name: string, values: string[]) {
    // Commit URL changes synchronously so controlled inputs do not roll back
    // while the router schedules a transition between keystrokes or clicks.
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        next.delete(name);
        next.delete("cursor");
        for (const value of values) if (value !== "") next.append(name, value);
        return next;
      },
      { flushSync: true },
    );
  }
  function handleClear() {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        for (const name of [
          ...textFilters,
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
            value={params.get("q") ?? ""}
            onChange={(event) => handleChange("q", [event.target.value])}
          />
        </label>
        <div className="field">
          <label htmlFor="candidate-company">Công ty</label>
          <input
            id="candidate-company"
            className="input"
            name="company"
            autoComplete="off"
            value={
              companyIsId
                ? selectedCompany.id === company
                  ? selectedCompany.name
                  : ""
                : company
            }
            placeholder={
              companyIsId
                ? "Đã chọn công ty từ danh mục"
                : "Gõ tên hoặc chọn gợi ý"
            }
            aria-describedby={companyIsId ? "selected-company" : undefined}
            onChange={(event) => handleChange("company", [event.target.value])}
          />
          {companyIsId && (
            <>
              <span id="selected-company" className="field__hint">
                Đã chọn công ty từ danh mục.
              </span>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleChange("company", [])}
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
                          setSelectedCompany({ id: item.id, name: item.name });
                          handleChange("company", [item.id]);
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
            value={params.get("title") ?? ""}
            onChange={(event) => handleChange("title", [event.target.value])}
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
              {!options.data && <span>Đang tải…</span>}
              {values.some(
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
          <legend title={estimatedExperienceHint}>Kinh nghiệm (dự tính)</legend>
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
                  value={params.get(name) ?? ""}
                  aria-invalid={!!error}
                  aria-describedby={
                    error ? "experience-filter-error" : "experience-filter-hint"
                  }
                  onChange={(event) => handleChange(name, [event.target.value])}
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
