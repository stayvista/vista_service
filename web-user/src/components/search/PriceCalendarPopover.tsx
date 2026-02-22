import { useEffect, useMemo, useRef, useState } from "react";
import { apiGet } from "../../api/client";
import { GuestState, PriceCalendarDay } from "./searchTypes";
import { toDateString } from "./searchState";

type PriceCalendarResponse = {
  days: PriceCalendarDay[];
};

type Props = {
  placeId?: string;
  cityHint?: string;
  currency: string;
  guests: GuestState;
  checkIn: string;
  checkOut: string;
  onApply: (next: { checkIn: string; checkOut: string }) => void;
};

type MonthGrid = {
  monthLabel: string;
  cells: Array<string | null>;
};

const WEEK_LABELS = ["월", "화", "수", "목", "금", "토", "일"];

export function PriceCalendarPopover({
  placeId,
  cityHint,
  currency,
  guests,
  checkIn,
  checkOut,
  onApply,
}: Props) {
  const [open, setOpen] = useState(false);
  const [anchorMonth, setAnchorMonth] = useState(() => startOfMonth(new Date()));
  const [draftCheckIn, setDraftCheckIn] = useState(checkIn);
  const [draftCheckOut, setDraftCheckOut] = useState(checkOut);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [days, setDays] = useState<PriceCalendarDay[]>([]);
  const wrapRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setDraftCheckIn(checkIn);
    setDraftCheckOut(checkOut);
  }, [checkIn, checkOut]);

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

  const resolvedPlaceId = useMemo(() => {
    if (placeId && placeId.trim()) {
      return placeId;
    }
    if (cityHint && cityHint.trim()) {
      return `city:${cityHint.trim()}`;
    }
    return "city:Seoul";
  }, [cityHint, placeId]);

  const from = useMemo(() => toDateString(startOfMonth(anchorMonth)), [anchorMonth]);
  const to = useMemo(() => toDateString(endOfMonth(addMonths(anchorMonth, 1))), [anchorMonth]);

  useEffect(() => {
    if (!open) {
      return;
    }

    const controller = new AbortController();
    const query = new URLSearchParams({
      place_id: resolvedPlaceId,
      from,
      to,
      currency,
      rooms: String(guests.rooms),
      adults: String(guests.adults),
      children: String(guests.children),
    });
    if (guests.childrenAges.length > 0) {
      query.set("children_ages", guests.childrenAges.slice(0, guests.children).join(","));
    }

    setLoading(true);
    setError(null);
    apiGet<PriceCalendarResponse>(`/v1/prices/calendar?${query.toString()}`, {}, controller.signal)
      .then((res) => {
        setDays(res.data.days ?? []);
      })
      .catch((e: unknown) => {
        if ((e as Error).name === "AbortError") {
          return;
        }
        setDays([]);
        setError("가격 캘린더를 불러오지 못했습니다.");
      })
      .finally(() => {
        setLoading(false);
      });

    return () => controller.abort();
  }, [currency, from, guests.adults, guests.children, guests.childrenAges, guests.rooms, open, resolvedPlaceId, to]);

  const priceByDate = useMemo(() => {
    const map = new Map<string, PriceCalendarDay>();
    days.forEach((day) => map.set(day.date, day));
    return map;
  }, [days]);

  const monthGrids = useMemo<MonthGrid[]>(() => {
    const first = buildMonthGrid(anchorMonth);
    const second = buildMonthGrid(addMonths(anchorMonth, 1));
    return [first, second];
  }, [anchorMonth]);

  const summary = useMemo(() => {
    if (!checkIn || !checkOut) {
      return "체크인 / 체크아웃 선택";
    }
    return formatRangeSummary(checkIn, checkOut);
  }, [checkIn, checkOut]);

  function selectDate(date: string) {
    if (!draftCheckIn || (draftCheckIn && draftCheckOut)) {
      setDraftCheckIn(date);
      setDraftCheckOut("");
      return;
    }

    if (date <= draftCheckIn) {
      setDraftCheckIn(date);
      setDraftCheckOut("");
      return;
    }

    setDraftCheckOut(date);
  }

  function resetDraft() {
    setDraftCheckIn(checkIn);
    setDraftCheckOut(checkOut);
  }

  return (
    <div className="date-picker" ref={wrapRef}>
      <button type="button" className="ghost-input" onClick={() => setOpen((prev) => !prev)}>
        {summary}
      </button>
      {open && (
        <div className="popover-card calendar-popover" role="dialog" aria-label="가격 캘린더">
          <header className="calendar-head">
            <button type="button" className="chip-btn" onClick={() => setAnchorMonth((prev) => addMonths(prev, -1))}>
              이전
            </button>
            <p>{currency} 기준 1박 예상 요금</p>
            <button type="button" className="chip-btn" onClick={() => setAnchorMonth((prev) => addMonths(prev, 1))}>
              다음
            </button>
          </header>
          <div className="calendar-grid-wrap">
            {monthGrids.map((month) => (
              <section key={month.monthLabel} className="calendar-month">
                <h4>{month.monthLabel}</h4>
                <div className="calendar-weekdays">
                  {WEEK_LABELS.map((label) => (
                    <span key={`${month.monthLabel}-${label}`}>{label}</span>
                  ))}
                </div>
                <div className="calendar-days">
                  {month.cells.map((date, index) => {
                    if (!date) {
                      return <span key={`${month.monthLabel}-empty-${index}`} className="calendar-day empty" />;
                    }

                    const price = priceByDate.get(date);
                    const inRange = Boolean(draftCheckIn && draftCheckOut && date >= draftCheckIn && date <= draftCheckOut);
                    const selectedEdge = date === draftCheckIn || date === draftCheckOut;

                    return (
                      <button
                        key={date}
                        type="button"
                        className={[
                          "calendar-day",
                          inRange ? "in-range" : "",
                          selectedEdge ? "selected" : "",
                        ].join(" ")}
                        onClick={() => selectDate(date)}
                      >
                        <strong>{Number(date.slice(-2))}</strong>
                        {loading ? (
                          <span className="day-price skeleton" />
                        ) : (
                          <span className="day-price">
                            {formatDayPrice(price?.min_price ?? null)}
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
          {error && <p className="error calendar-error">{error}</p>}
          <div className="popover-actions">
            <button
              type="button"
              className="chip-btn"
              onClick={() => {
                resetDraft();
                setOpen(false);
              }}
            >
              취소
            </button>
            <button
              type="button"
              disabled={!draftCheckIn || !draftCheckOut}
              onClick={() => {
                if (!draftCheckIn || !draftCheckOut) {
                  return;
                }
                onApply({ checkIn: draftCheckIn, checkOut: draftCheckOut });
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

function buildMonthGrid(monthStart: Date): MonthGrid {
  const startWeekday = (monthStart.getDay() + 6) % 7;
  const daysInMonth = endOfMonth(monthStart).getDate();
  const cells: Array<string | null> = [];

  for (let i = 0; i < startWeekday; i += 1) {
    cells.push(null);
  }

  for (let day = 1; day <= daysInMonth; day += 1) {
    cells.push(toDateString(new Date(monthStart.getFullYear(), monthStart.getMonth(), day)));
  }

  while (cells.length % 7 !== 0) {
    cells.push(null);
  }

  return {
    monthLabel: `${monthStart.getFullYear()}년 ${monthStart.getMonth() + 1}월`,
    cells,
  };
}

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

function endOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth() + 1, 0);
}

function addMonths(date: Date, count: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + count, 1);
}

function formatDayPrice(price: number | null): string {
  if (price == null || price <= 0) {
    return "-";
  }
  if (price >= 1000) {
    return `${Math.round(price / 1000)}K`;
  }
  return String(price);
}

function formatRangeSummary(checkIn: string, checkOut: string): string {
  const [inYear, inMonth, inDay] = checkIn.split("-");
  const [outYear, outMonth, outDay] = checkOut.split("-");
  if (!inYear || !inMonth || !inDay || !outYear || !outMonth || !outDay) {
    return `${checkIn} ~ ${checkOut}`;
  }
  if (inYear === outYear) {
    return `${inYear}-${inMonth}-${inDay} ~ ${outMonth}-${outDay}`;
  }
  return `${checkIn} ~ ${checkOut}`;
}
