import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";

type QueueJoinData = {
  queue_key: string;
  ticket: string;
  position: number;
  estimated_wait_seconds: number;
};

type QueueStatusData = {
  state: "WAITING" | "ADMITTED" | "EXPIRED";
  position: number;
  estimated_wait_seconds: number;
  admit_token: string | null;
};

type ApiError = {
  code?: string;
  message?: string;
};

export function CheckoutBookingPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState("대기");
  const [bookingId, setBookingId] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);
  const [queueTicket, setQueueTicket] = useState<string | null>(null);
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueWaitSeconds, setQueueWaitSeconds] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queuePollRef = useRef<number | null>(null);

  const holdBody = useMemo(() => ({
    room_type_id: Number(params.get("room_type_id") ?? "0"),
    check_in: params.get("check_in") ?? "2026-02-10",
    check_out: params.get("check_out") ?? "2026-02-12",
    rooms: 1,
    guests: { adults: 2, children: 0 },
    price: { currency: "KRW", amount_total: 120000 },
  }), [params]);

  useEffect(() => {
    return () => {
      if (queuePollRef.current !== null) {
        window.clearInterval(queuePollRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (!expiresAt) {
      setRemainingSeconds(null);
      return;
    }

    const tick = () => {
      const seconds = Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
      setRemainingSeconds(seconds);
      if (seconds === 0) {
        setStatus("HOLD 만료");
      }
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [expiresAt]);

  function toApiError(value: unknown): ApiError {
    if (typeof value === "object" && value !== null) {
      return value as ApiError;
    }
    return {};
  }

  function queueKey() {
    return `accom:${holdBody.room_type_id}:${holdBody.check_in}:${holdBody.check_out}`;
  }

  function resetQueueState() {
    setQueueTicket(null);
    setQueuePosition(null);
    setQueueWaitSeconds(null);
    if (queuePollRef.current !== null) {
      window.clearInterval(queuePollRef.current);
      queuePollRef.current = null;
    }
  }

  async function attemptHold(queueToken?: string) {
    return apiPost<{ booking_id: string; expires_at: string }>(
      "/v1/bookings/holds",
      holdBody,
      {
        "Idempotency-Key": crypto.randomUUID(),
        "X-User-Id": "1001",
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
  }

  async function handleQueueFlow() {
    setStatus("대기열 입장 중");
    const join = await apiPost<QueueJoinData>(
      "/v1/queue/join",
      { queue_key: queueKey() },
      { "X-User-Id": "1001" }
    );
    setQueueTicket(join.data.ticket);
    setQueuePosition(join.data.position);
    setQueueWaitSeconds(join.data.estimated_wait_seconds);
    setStatus("대기열 대기중");

    const poll = async () => {
      const statusResult = await apiGet<QueueStatusData>(`/v1/queue/status?ticket=${encodeURIComponent(join.data.ticket)}`);
      setQueuePosition(statusResult.data.position);
      setQueueWaitSeconds(statusResult.data.estimated_wait_seconds);

      if (statusResult.data.state === "EXPIRED") {
        resetQueueState();
        setStatus("대기열 만료");
        setError("QUEUE_TOKEN_INVALID: 대기열 티켓이 만료되었습니다.");
        return;
      }

      if (statusResult.data.state === "ADMITTED" && statusResult.data.admit_token) {
        resetQueueState();
        setStatus("입장 허용, HOLD 재시도");
        try {
          const hold = await attemptHold(statusResult.data.admit_token);
          setBookingId(hold.data.booking_id);
          setExpiresAt(hold.data.expires_at);
          setStatus("HOLD 완료");
        } catch (holdError) {
          const err = toApiError(holdError);
          setStatus("HOLD 실패");
          setError(`${err.code ?? "ERROR"}: ${err.message ?? "hold 실패"}`);
        }
      }
    };

    if (queuePollRef.current !== null) {
      window.clearInterval(queuePollRef.current);
    }
    queuePollRef.current = window.setInterval(() => {
      void poll().catch(() => {
        setStatus("대기열 대기중");
      });
    }, 2000);
    await poll();
  }

  async function createHold() {
    resetQueueState();
    setError(null);
    setBookingId(null);
    setExpiresAt(null);
    setStatus("HOLD 생성 중");
    try {
      const response = await attemptHold();
      setBookingId(response.data.booking_id);
      setExpiresAt(response.data.expires_at);
      setStatus("HOLD 완료");
    } catch (e) {
      const err = toApiError(e);
      if (err.code === "QUEUE_REQUIRED") {
        await handleQueueFlow().catch((queueError: unknown) => {
          const queueErr = toApiError(queueError);
          setStatus("대기열 실패");
          setError(`${queueErr.code ?? "ERROR"}: ${queueErr.message ?? "queue 처리 실패"}`);
        });
        return;
      }
      setStatus("HOLD 실패");
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "hold 실패"}`);
    }
  }

  async function confirm() {
    if (!bookingId || remainingSeconds === 0) return;
    setError(null);
    setStatus("CONFIRM 진행 중");
    try {
      await apiPost(
        `/v1/bookings/${bookingId}/confirm`,
        { payment_method: "CARD", payment_token: "paytok_test", agree_terms: true },
        { "Idempotency-Key": crypto.randomUUID(), "X-User-Id": "1001" }
      );
      navigate("/booking/complete");
    } catch (e) {
      const err = toApiError(e);
      setStatus("CONFIRM 실패");
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "confirm 실패"}`);
    }
  }

  const countdownText = remainingSeconds === null
    ? "HOLD 생성 전"
    : `${Math.floor(remainingSeconds / 60)}:${String(remainingSeconds % 60).padStart(2, "0")}`;

  const isExpired = remainingSeconds === 0;

  return (
    <section className="page">
      <h2>예약 체크아웃</h2>
      <p>상태: {status}</p>
      <p>남은 시간: {countdownText}</p>
      {expiresAt && <p>만료시각: {expiresAt}</p>}
      {queueTicket && (
        <div className="queue-box">
          <p>대기 티켓: {queueTicket}</p>
          <p>현재 순번: {queuePosition ?? "-"}</p>
          <p>예상 대기: {queueWaitSeconds ?? "-"}초</p>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button onClick={createHold}>HOLD 생성</button>
        <button disabled={!bookingId || isExpired} onClick={confirm}>CONFIRM</button>
      </div>
      {isExpired && <p className="warning">HOLD가 만료되어 다시 생성이 필요합니다.</p>}
    </section>
  );
}
