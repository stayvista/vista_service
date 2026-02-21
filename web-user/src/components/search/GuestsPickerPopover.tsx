import { useEffect, useMemo, useRef, useState } from "react";
import { GuestState } from "./searchTypes";

type Props = {
  value: GuestState;
  onApply: (next: GuestState) => void;
};

export function GuestsPickerPopover({ value, onApply }: Props) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<GuestState>(value);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => setDraft(value), [value]);

  useEffect(() => {
    function onOutsideClick(event: MouseEvent) {
      const target = event.target as Node | null;
      if (target && wrapRef.current?.contains(target)) {
        return;
      }
      setOpen(false);
    }
    document.addEventListener("mousedown", onOutsideClick);
    return () => document.removeEventListener("mousedown", onOutsideClick);
  }, []);

  const summary = useMemo(() => {
    return `객실 ${value.rooms} · 성인 ${value.adults} · 아동 ${value.children}`;
  }, [value.adults, value.children, value.rooms]);

  function patch(next: Partial<GuestState>) {
    setDraft((prev) => {
      const merged: GuestState = {
        rooms: next.rooms ?? prev.rooms,
        adults: next.adults ?? prev.adults,
        children: next.children ?? prev.children,
        childrenAges: next.childrenAges ?? prev.childrenAges,
      };
      const children = merged.children;
      const normalizedAges = [...merged.childrenAges].slice(0, children);
      while (normalizedAges.length < children) {
        normalizedAges.push(7);
      }
      merged.childrenAges = normalizedAges.map((age) => Math.max(0, Math.min(17, age)));
      merged.rooms = Math.max(1, Math.min(8, merged.rooms));
      merged.adults = Math.max(1, Math.min(16, merged.adults));
      merged.children = Math.max(0, Math.min(8, merged.children));
      return merged;
    });
  }

  return (
    <div className="guests-picker" ref={wrapRef}>
      <button
        type="button"
        className="ghost-input"
        onClick={() => setOpen((prev) => !prev)}
      >
        {summary}
      </button>
      {open && (
        <div className="popover-card guests-popover">
          <CounterRow
            label="객실"
            value={draft.rooms}
            min={1}
            max={8}
            onChange={(next) => patch({ rooms: next })}
          />
          <CounterRow
            label="성인"
            value={draft.adults}
            min={1}
            max={16}
            onChange={(next) => patch({ adults: next })}
          />
          <CounterRow
            label="아동"
            value={draft.children}
            min={0}
            max={8}
            onChange={(next) => patch({ children: next })}
          />
          {draft.children > 0 && (
            <div className="children-age-grid">
              {draft.childrenAges.map((age, index) => (
                <label key={`child-age-${index}`}>
                  아동 {index + 1}
                  <select
                    value={age}
                    onChange={(event) => {
                      const nextAges = [...draft.childrenAges];
                      nextAges[index] = Number(event.target.value);
                      patch({ childrenAges: nextAges });
                    }}
                  >
                    {Array.from({ length: 18 }, (_, i) => i).map((ageValue) => (
                      <option key={ageValue} value={ageValue}>
                        {ageValue}세
                      </option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
          )}
          <div className="popover-actions">
            <button
              type="button"
              className="chip-btn"
              onClick={() => {
                setDraft(value);
                setOpen(false);
              }}
            >
              취소
            </button>
            <button
              type="button"
              onClick={() => {
                onApply(draft);
                setOpen(false);
              }}
            >
              적용
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

type CounterRowProps = {
  label: string;
  value: number;
  min: number;
  max: number;
  onChange: (next: number) => void;
};

function CounterRow({ label, value, min, max, onChange }: CounterRowProps) {
  return (
    <div className="counter-row">
      <span>{label}</span>
      <div className="counter-controls">
        <button type="button" className="counter-btn" onClick={() => onChange(Math.max(min, value - 1))}>-</button>
        <strong>{value}</strong>
        <button type="button" className="counter-btn" onClick={() => onChange(Math.min(max, value + 1))}>+</button>
      </div>
    </div>
  );
}
