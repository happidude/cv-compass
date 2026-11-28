import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { useSearchParams } from "react-router-dom";
import { ChevronDown, Search, X } from "lucide-react";
import { Button, ErrorNotice } from "../../shared/ui";
import { cn } from "../../shared/utils";
import { useCompanySuggestions, useSearchOptions } from "./queries";
import {
  isCompanyId,
  multiFilters,
  retiredFilters,
  searchParamNames,
} from "./search-filters";

/** Text fields are applied only on submit; each group is one URL concern. */
const textGroups = {
  q: ["q"],
  company: ["company", "companyId", "companyName"],
  title: ["title"],
} as const;
type TextGroup = keyof typeof textGroups;
const textGroupNames = Object.keys(textGroups) as TextGroup[];

function readTextFilters(params: URLSearchParams) {
  const companyId = params.get("companyId") ?? "";
  return {
    q: params.get("q") ?? "",
    company: isCompanyId(companyId) ? "" : (params.get("company") ?? ""),
    companyId,
    companyName: isCompanyId(companyId)
      ? (params.get("companyName") ?? "")
      : "",
    title: params.get("title") ?? "",
  };
}
type TextDraft = ReturnType<typeof readTextFilters>;

function isDirty(draft: TextDraft, applied: TextDraft, group: TextGroup) {
  return textGroups[group].some((name) => draft[name] !== applied[name]);
}
function writeGroup(next: URLSearchParams, draft: TextDraft, group: TextGroup) {
  for (const name of textGroups[group]) {
    next.delete(name);
    if (draft[name] !== "") next.set(name, draft[name]);
  }
}

type Choice = { code: string; label: string };
type Options = ReturnType<typeof useSearchOptions>["data"];
function choicesFor(
  options: Options,
  kind: (typeof multiFilters)[number]["kind"],
) {
  return (
    options?.[kind]?.flatMap((entry): Choice[] => {
      if (typeof entry === "string") return [{ code: entry, label: entry }];
      return entry.label.trim() ? [entry] : [];
    }) ?? []
  );
}
/** Case- and accent-insensitive match so "ha noi" finds "Hà Nội". */
function fold(text: string) {
  return text
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/đ/gi, "d")
    .toLowerCase();
}

export function CandidateSearchForm() {
  const [params, setParams] = useSearchParams();
  const options = useSearchOptions();
  const applied = readTextFilters(params);
  const [draft, setDraft] = useState(() => applied);
  const [openGroup, setOpenGroup] = useState<string>();
  const url = params.toString();
  const [previousUrl, setPreviousUrl] = useState(url);
  if (previousUrl !== url) {
    // Untouched fields follow the URL (Back/Forward, chips, shared links);
    // text the user typed but has not applied yet is kept as it is.
    const before = readTextFilters(new URLSearchParams(previousUrl));
    setPreviousUrl(url);
    setDraft((current) => {
      const next = { ...current };
      for (const group of textGroupNames)
        if (!isDirty(current, before, group))
          for (const name of textGroups[group]) next[name] = applied[name];
      return next;
    });
  }
  const dirty = textGroupNames.some((group) => isDirty(draft, applied, group));

  function update(change: (next: URLSearchParams) => void) {
    const next = new URLSearchParams(params);
    // Any filter change restarts pagination; retired filters are dropped.
    next.delete("cursor");
    for (const name of retiredFilters) next.delete(name);
    change(next);
    // Re-submitting the same search must not stack duplicate history entries.
    if (next.toString() === params.toString()) return;
    setParams(next, { flushSync: true });
  }
  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    update((next) => {
      for (const group of textGroupNames) writeGroup(next, draft, group);
    });
  }
  function handleTextChange(name: "q" | "company" | "title", value: string) {
    setDraft((current) => ({
      ...current,
      [name]: value,
      ...(name === "company" ? { companyId: "", companyName: "" } : {}),
    }));
  }
  function handleCompanySelection(id: string, name: string) {
    const nextDraft = {
      ...draft,
      company: "",
      companyId: id,
      companyName: name,
    };
    setDraft(nextDraft);
    // A catalog pick applies at once, like a checkbox; other pending text
    // stays pending.
    update((next) => writeGroup(next, nextDraft, "company"));
  }
  function removeText(group: TextGroup) {
    update((next) => {
      for (const name of textGroups[group]) next.delete(name);
    });
  }
  function handleChoices(name: string, values: string[]) {
    update((next) => {
      next.delete(name);
      for (const value of values) if (value !== "") next.append(name, value);
    });
  }
  function handleClear() {
    setDraft(readTextFilters(new URLSearchParams()));
    setOpenGroup(undefined);
    update((next) => {
      for (const name of searchParamNames) next.delete(name);
    });
  }

  const chips: { key: string; text: string; remove: () => void }[] = [];
  if (applied.q.trim())
    chips.push({
      key: "q",
      text: `Từ khóa: ${applied.q}`,
      remove: () => removeText("q"),
    });
  if (isCompanyId(applied.companyId) || applied.company.trim())
    chips.push({
      key: "company",
      text: `Công ty: ${
        isCompanyId(applied.companyId)
          ? applied.companyName || "đã chọn từ danh mục"
          : applied.company
      }`,
      remove: () => removeText("company"),
    });
  if (applied.title.trim())
    chips.push({
      key: "title",
      text: `Chức danh: ${applied.title}`,
      remove: () => removeText("title"),
    });
  for (const { name, kind, label } of multiFilters) {
    const values = params.getAll(name).filter((value) => value.trim());
    const choices = choicesFor(options.data, kind);
    for (const value of values) {
      // Raw codes are never shown, even when the taxonomy lacks a label.
      const choice = choices.find((item) => item.code === value);
      chips.push({
        key: `${name}:${value}`,
        text: `${label}: ${
          choice?.label ??
          (options.data ? "lựa chọn chưa có nhãn" : "đang tải nhãn…")
        }`,
        remove: () =>
          handleChoices(
            name,
            values.filter((item) => item !== value),
          ),
      });
    }
  }

  return (
    <form
      className="panel search-form"
      role="search"
      aria-label="Tìm kiếm ứng viên"
      onSubmit={handleSubmit}
    >
      <div className="search-form__query">
        <label className="field">
          Từ khóa
          <input
            className="input"
            name="q"
            autoComplete="off"
            value={draft.q}
            onChange={(event) => handleTextChange("q", event.target.value)}
          />
        </label>
        <CompanyField
          draft={draft}
          onType={(value) => handleTextChange("company", value)}
          onSelect={handleCompanySelection}
          onDeselect={() => removeText("company")}
        />
        <label className="field">
          Chức danh
          <input
            className="input"
            name="title"
            autoComplete="off"
            value={draft.title}
            onChange={(event) => handleTextChange("title", event.target.value)}
          />
        </label>
        <Button type="submit" className="search-form__submit">
          <Search size={16} aria-hidden="true" />
          Tìm kiếm
        </Button>
      </div>
      <p className="search-form__pending" aria-live="polite">
        {dirty ? "Bấm Tìm kiếm để áp dụng từ khóa" : ""}
      </p>
      {options.error && (
        <ErrorNotice
          error={options.error}
          retry={() => void options.refetch()}
        />
      )}
      <div className="search-form__groups">
        {multiFilters.map(({ name, kind, label }) => (
          <FilterGroup
            key={name}
            label={label}
            choices={choicesFor(options.data, kind)}
            values={params.getAll(name).filter((value) => value.trim())}
            state={options.error ? "error" : options.data ? "ready" : "loading"}
            open={openGroup === name}
            onToggle={() =>
              setOpenGroup((current) => (current === name ? undefined : name))
            }
            onClose={() =>
              setOpenGroup((current) =>
                current === name ? undefined : current,
              )
            }
            onChange={(values) => handleChoices(name, values)}
          />
        ))}
      </div>
      {(chips.length > 0 || dirty) && (
        <div className="search-form__applied">
          <p className="search-form__count">
            {chips.length
              ? `Đang áp dụng ${chips.length} bộ lọc`
              : "Chưa áp dụng bộ lọc"}
          </p>
          {chips.length > 0 && (
            <ul className="filter-chips" aria-label="Bộ lọc đang áp dụng">
              {chips.map((chip) => (
                <li key={chip.key} className="filter-chip">
                  <span>{chip.text}</span>
                  <button
                    type="button"
                    className="filter-chip__remove"
                    aria-label={`Bỏ lọc ${chip.text}`}
                    onClick={chip.remove}
                  >
                    <X size={14} aria-hidden="true" />
                  </button>
                </li>
              ))}
            </ul>
          )}
          <Button variant="ghost" size="sm" onClick={handleClear}>
            Xóa lọc
          </Button>
        </div>
      )}
    </form>
  );
}

function CompanyField({
  draft,
  onType,
  onSelect,
  onDeselect,
}: {
  draft: TextDraft;
  onType: (value: string) => void;
  onSelect: (id: string, name: string) => void;
  onDeselect: () => void;
}) {
  const id = useId();
  const listId = `${id}-list`;
  const hintId = `${id}-hint`;
  const companySelected = isCompanyId(draft.companyId) && !!draft.companyName;
  const suggestionText = companySelected ? "" : draft.company;
  const [debounced, setDebounced] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(suggestionText), 300);
    return () => clearTimeout(timer);
  }, [suggestionText]);
  // Suggest only for text the user typed here, so a shared link or
  // Back/Forward never pops the list open (or fetches) by itself.
  const [typed, setTyped] = useState<string>();
  const live = !!suggestionText && suggestionText === typed;
  const current = live && suggestionText === debounced;
  const suggestions = useCompanySuggestions(current ? debounced : "");
  const [dismissed, setDismissed] = useState(false);
  const [active, setActive] = useState(-1);
  const loading = current && !dismissed && suggestions.isFetching;
  const available = current ? (suggestions.data ?? []) : [];
  const items = dismissed ? [] : available;
  const expanded = items.length > 0;
  const activeIndex = active < items.length ? active : -1;

  function select(index: number) {
    const item = items[index];
    setActive(-1);
    onSelect(item.id, item.name);
  }
  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (!available.length) return;
      event.preventDefault();
      setDismissed(false);
      const last = available.length - 1;
      setActive((index) =>
        event.key === "ArrowDown"
          ? index >= last
            ? 0
            : index + 1
          : index <= 0
            ? last
            : index - 1,
      );
    } else if (event.key === "Enter" && expanded && activeIndex >= 0) {
      // Enter picks the highlighted suggestion instead of submitting.
      event.preventDefault();
      select(activeIndex);
    } else if (event.key === "Enter") {
      // Enter submits the form; the suggestion list closes with it.
      setDismissed(true);
      setActive(-1);
    } else if (event.key === "Escape" && expanded) {
      event.preventDefault();
      setDismissed(true);
      setActive(-1);
    }
  }

  return (
    <div className="field combobox">
      <label htmlFor={`${id}-input`}>Công ty</label>
      <div className="combobox__control">
        <input
          id={`${id}-input`}
          className="input"
          name="company"
          role="combobox"
          autoComplete="off"
          aria-autocomplete="list"
          aria-expanded={expanded}
          aria-controls={listId}
          aria-activedescendant={
            activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined
          }
          aria-describedby={companySelected ? hintId : undefined}
          value={companySelected ? draft.companyName : draft.company}
          placeholder="Gõ tên hoặc chọn gợi ý"
          onChange={(event) => {
            setDismissed(false);
            setActive(-1);
            setTyped(event.target.value);
            onType(event.target.value);
          }}
          onKeyDown={handleKeyDown}
          onBlur={() => {
            setDismissed(true);
            setActive(-1);
          }}
        />
        <ul
          id={listId}
          role="listbox"
          aria-label="Gợi ý công ty"
          className="combobox__list"
          hidden={!expanded}
        >
          {items.map((item, index) => (
            <li
              key={item.id}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={index === activeIndex}
              className="combobox__option"
              // Keep focus in the input so the list does not blur-close first.
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => select(index)}
            >
              {item.name}
              {item.parentName ? ` (${item.parentName})` : ""}
            </li>
          ))}
        </ul>
      </div>
      {companySelected && (
        <span className="field__hint combobox__hint">
          <span id={hintId}>Đã chọn công ty từ danh mục.</span>
          <Button variant="ghost" size="sm" onClick={onDeselect}>
            Bỏ chọn công ty
          </Button>
        </span>
      )}
      {/* Mounted for good: live regions inserted on the fly are often missed. */}
      <span className="field__hint combobox__status" role="status">
        {loading ? "Đang tải gợi ý…" : ""}
      </span>
      {current && !dismissed && suggestions.error && (
        <ErrorNotice
          error={suggestions.error}
          retry={() => void suggestions.refetch()}
        />
      )}
    </div>
  );
}

function FilterGroup({
  label,
  choices,
  values,
  state,
  open,
  onToggle,
  onClose,
  onChange,
}: {
  label: string;
  choices: Choice[];
  values: string[];
  state: "loading" | "error" | "ready";
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  onChange: (values: string[]) => void;
}) {
  const id = useId();
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLFieldSetElement>(null);
  const [query, setQuery] = useState("");
  // A reopened group starts unfiltered so no choice looks missing.
  if (!open && query) setQuery("");
  const [flipped, setFlipped] = useState(false);
  useLayoutEffect(() => {
    if (!open || !panel.current || !trigger.current) return;
    // Open leftwards when the popover would run past the viewport edge.
    const width = panel.current.getBoundingClientRect().width;
    const left = trigger.current.getBoundingClientRect().left;
    setFlipped(left + width > document.documentElement.clientWidth);
  }, [open]);
  useEffect(() => {
    if (!open) return;
    function handlePointer(event: MouseEvent) {
      if (!wrapper.current?.contains(event.target as Node)) onClose();
    }
    document.addEventListener("mousedown", handlePointer);
    return () => document.removeEventListener("mousedown", handlePointer);
  }, [open, onClose]);
  const unlabelled = values.some(
    (value) => !choices.some((choice) => choice.code === value),
  );
  const needle = fold(query.trim());
  const visible = needle
    ? choices.filter((choice) => fold(choice.label).includes(needle))
    : choices;

  return (
    <div
      ref={wrapper}
      className="filter-group"
      onBlur={(event) => {
        // Tabbing out closes the popover; pointer clicks are handled above.
        const next = event.relatedTarget;
        if (open && next && !wrapper.current?.contains(next)) onClose();
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape" && open) {
          event.preventDefault();
          onClose();
          trigger.current?.focus();
        }
      }}
    >
      <button
        ref={trigger}
        type="button"
        className={cn("filter-group__trigger", values.length && "is-active")}
        aria-expanded={open}
        aria-controls={`${id}-panel`}
        onClick={onToggle}
      >
        {label}
        {values.length > 0 && (
          <>
            <span className="sr-only">{`, đã chọn ${values.length}`}</span>
            <span className="filter-group__count" aria-hidden="true">
              {values.length}
            </span>
          </>
        )}
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      <fieldset
        ref={panel}
        id={`${id}-panel`}
        className={cn("filter-group__panel", flipped && "is-flipped")}
        hidden={!open}
      >
        <legend className="sr-only">{label}</legend>
        {!open ? null : state === "error" ? (
          <p className="field__hint">Không tải được lựa chọn.</p>
        ) : state === "loading" ? (
          <p className="field__hint">Đang tải…</p>
        ) : (
          <>
            {choices.length > 8 && (
              <input
                className="input filter-group__filter"
                aria-label={`Lọc nhanh ${label}`}
                placeholder="Lọc nhanh…"
                autoComplete="off"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={(event) => {
                  // Enter here narrows the list; it must not submit the form.
                  if (event.key === "Enter") event.preventDefault();
                }}
              />
            )}
            {unlabelled && (
              <p className="field__hint">
                Một lựa chọn chưa có nhãn. Bạn có thể xóa lọc để đặt lại.
              </p>
            )}
            <div className="filter-group__choices">
              {visible.map((choice) => (
                <label key={choice.code} className="checkbox">
                  <input
                    type="checkbox"
                    value={choice.code}
                    checked={values.includes(choice.code)}
                    onChange={(event) =>
                      onChange(
                        event.target.checked
                          ? [...values, choice.code]
                          : values.filter((value) => value !== choice.code),
                      )
                    }
                  />
                  <span>{choice.label}</span>
                </label>
              ))}
              {visible.length === 0 && (
                <p className="field__hint">Không có lựa chọn phù hợp.</p>
              )}
            </div>
          </>
        )}
      </fieldset>
    </div>
  );
}
