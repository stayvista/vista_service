import { FormEvent, useMemo, useState } from "react";
import { apiPut } from "../api/client";

type ApiError = { code?: string; message?: string; details?: Record<string, unknown> };

function parseIsoDate(value: string): Date {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function formatIsoDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function addDays(dateIso: string, days: number): string {
  const date = parseIsoDate(dateIso);
  date.setDate(date.getDate() + days);
  return formatIsoDate(date);
}

function monthDates(monthValue: string): string[] {
  const [year, month] = monthValue.split("-").map(Number);
  const cursor = new Date(year, month - 1, 1);
  const days: string[] = [];
  while (cursor.getMonth() === month - 1) {
    days.push(formatIsoDate(cursor));
    cursor.setDate(cursor.getDate() + 1);
  }
  return days;
}

function daysBetween(startInclusive: string, endExclusive: string): string[] {
  const result: string[] = [];
  const cursor = parseIsoDate(startInclusive);
  const end = parseIsoDate(endExclusive);
  while (cursor < end) {
    result.push(formatIsoDate(cursor));
    cursor.setDate(cursor.getDate() + 1);
  }
  return result;
}

export function InventoryPage() {
  const [roomTypeId, setRoomTypeId] = useState(1);
  const [startDate, setStartDate] = useState("2026-02-10");
  const [endDate, setEndDate] = useState("2026-02-20");
  const [total, setTotal] = useState(20);
  const [month, setMonth] = useState("2026-02");
  const [savedTotals, setSavedTotals] = useState<Record<string, number>>({});
  const [draftTotals, setDraftTotals] = useState<Record<string, number>>({});
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [selectedTotal, setSelectedTotal] = useState(20);
  const [conflictDate, setConflictDate] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const visibleDates = useMemo(() => monthDates(month), [month]);

  function displayedTotal(date: string): number {
    if (Object.prototype.hasOwnProperty.call(draftTotals, date)) return draftTotals[date];
    if (Object.prototype.hasOwnProperty.call(savedTotals, date)) return savedTotals[date];
    return total;
  }

  async function applyRange(start: string, endExclusive: string, amount: number) {
    await apiPut(`/v1/admin/room-types/${roomTypeId}/inventory`, {
      start_date: start,
      end_date: endExclusive,
      total: amount,
    });
    const dates = daysBetween(start, endExclusive);
    setSavedTotals((prev) => {
      const next = { ...prev };
      dates.forEach((date) => {
        next[date] = amount;
      });
      return next;
    });
    setDraftTotals((prev) => {
      const next = { ...prev };
      dates.forEach((date) => {
        delete next[date];
      });
      return next;
    });
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    setConflictDate(null);
    if (startDate >= endDate) {
      setError("VALIDATION_ERROR: 시작일은 종료일보다 앞서야 합니다.");
      return;
    }
    try {
      await applyRange(startDate, endDate, total);
      setMessage("재고가 반영되었습니다.");
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "INVENTORY_TOTAL_BELOW_COMMITTED") {
        const conflictDate = typeof err.details?.conflict_date === "string" ? err.details.conflict_date : undefined;
        if (conflictDate) {
          setConflictDate(conflictDate);
          const monthKey = conflictDate.slice(0, 7);
          if (month !== monthKey) setMonth(monthKey);
        }
        setError(
          `INVENTORY_TOTAL_BELOW_COMMITTED: ${conflictDate ?? "일부 날짜의 hold/sold가 현재 total보다 큽니다."}`
        );
        return;
      }
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "재고 반영 실패"}`);
    }
  }

  async function applySelectedDate() {
    if (!selectedDate) return;
    setMessage(null);
    setError(null);
    setConflictDate(null);
    try {
      await applyRange(selectedDate, addDays(selectedDate, 1), selectedTotal);
      setMessage(`${selectedDate} 재고를 ${selectedTotal}로 반영했습니다.`);
      setSelectedTotal(selectedTotal);
    } catch (e) {
      const err = e as ApiError;
      if (err.code === "INVENTORY_TOTAL_BELOW_COMMITTED") {
        setConflictDate(selectedDate);
      }
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "단일 일자 반영 실패"}`);
    }
  }

  return (
    <div>
      <h2>재고 캘린더(범위 적용)</h2>
      <div className="row-form">
        <label>
          대상 월
          <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
        </label>
      </div>
      <form className="row-form" onSubmit={onSubmit}>
        <input type="number" value={roomTypeId} onChange={(e) => setRoomTypeId(Number(e.target.value))} />
        <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
        <input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />
        <input type="number" value={total} onChange={(e) => setTotal(Number(e.target.value))} />
        <button type="submit">적용</button>
      </form>
      <p>종료일은 미포함입니다. 예: 2026-02-10 ~ 2026-02-11은 2월 10일 하루만 반영.</p>
      <div className="inventory-grid">
        {visibleDates.map((date) => (
          <div
            key={date}
            className={`inventory-cell${selectedDate === date ? " selected" : ""}${conflictDate === date ? " conflict" : ""}`}
            onClick={() => {
              setSelectedDate(date);
              setSelectedTotal(displayedTotal(date));
            }}
          >
            <span className="date-label">{date.slice(-2)}</span>
            <input
              type="number"
              value={displayedTotal(date)}
              onChange={(e) => {
                const value = Number(e.target.value);
                setDraftTotals((prev) => ({ ...prev, [date]: value }));
                if (selectedDate === date) setSelectedTotal(value);
              }}
            />
          </div>
        ))}
      </div>
      <div className="row-form">
        <input value={selectedDate ?? ""} readOnly placeholder="선택된 날짜" />
        <input
          type="number"
          value={selectedTotal}
          onChange={(e) => setSelectedTotal(Number(e.target.value))}
          disabled={!selectedDate}
        />
        <button type="button" onClick={applySelectedDate} disabled={!selectedDate}>선택일 저장</button>
      </div>
      <p>미반영 변경: {Object.keys(draftTotals).length}일</p>
      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
