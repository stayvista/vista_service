import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
type QueueJoinData = { ticket: string; position: number; estimated_wait_seconds: number };
type QueueStatusData = {
  state: "WAITING" | "ADMITTED" | "EXPIRED";
  position: number;
  estimated_wait_seconds: number;
  admit_token: string | null;
};

export function CheckoutTicketPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [orderId, setOrderId] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);
  const [status, setStatus] = useState("대기");
  const [queueTicket, setQueueTicket] = useState<string | null>(null);
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueWaitSeconds, setQueueWaitSeconds] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queuePollRef = useRef<number | null>(null);

  const eventId = Number(params.get("event_id") ?? "0");
  const quantity = useMemo(
    () => Math.max(1, Number(params.get("quantity") ?? "1")),
    [params]
  );

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

  function toError(value: unknown): ApiError {
    if (typeof value === "object" && value !== null) return value as ApiError;
    return {};
  }

  function resetQueue() {
    setQueueTicket(null);
    setQueuePosition(null);
    setQueueWaitSeconds(null);
    if (queuePollRef.current !== null) {
      window.clearInterval(queuePollRef.current);
      queuePollRef.current = null;
    }
  }

  async function attemptHold(queueToken?: string) {
    return apiPost<{ order_id: string; expires_at: string }>(
      "/v1/tickets/orders/holds",
      {
        event_id: eventId,
        quantity,
        price: { currency: "KRW", amount_total: 43000 * quantity },
      },
      {
        "Idempotency-Key": crypto.randomUUID(),
        "X-User-Id": "1001",
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
  }

  async function attemptConfirm(queueToken?: string) {
    if (!orderId || remainingSeconds === 0) return;
    await apiPost(
      `/v1/tickets/orders/${orderId}/confirm`,
      { payment_method: "CARD", payment_token: "paytok_test" },
      {
        "Idempotency-Key": crypto.randomUUID(),
        "X-User-Id": "1001",
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
    const done = new URLSearchParams({
      type: "ticket",
      order_id: orderId,
    });
    navigate(`/booking/complete?${done.toString()}`);
  }

  async function handleQueue() {
    setStatus("대기열 입장 중");
    const join = await apiPost<QueueJoinData>(
      "/v1/queue/join",
      { queue_key: `ticket:${eventId}` },
      { "X-User-Id": "1001" }
    );
    setQueueTicket(join.data.ticket);
    setQueuePosition(join.data.position);
    setQueueWaitSeconds(join.data.estimated_wait_seconds);
    setStatus("대기열 대기중");

    const poll = async () => {
      const result = await apiGet<QueueStatusData>(`/v1/queue/status?ticket=${encodeURIComponent(join.data.ticket)}`);
      setQueuePosition(result.data.position);
      setQueueWaitSeconds(result.data.estimated_wait_seconds);

      if (result.data.state === "EXPIRED") {
        resetQueue();
        setStatus("대기열 만료");
        setError("QUEUE_TOKEN_INVALID: 대기열 입장권이 만료되었습니다.");
        return;
      }
      if (result.data.state === "ADMITTED" && result.data.admit_token) {
        resetQueue();
        setStatus("입장 허용, HOLD 재시도");
        const hold = await attemptHold(result.data.admit_token);
        setOrderId(hold.data.order_id);
        setExpiresAt(hold.data.expires_at);
        setStatus("HOLD 완료");
      }
    };

    queuePollRef.current = window.setInterval(() => {
      void poll().catch(() => {
        setStatus("대기열 대기중");
      });
    }, 2000);
    await poll();
  }

  async function handleConfirmQueue() {
    setStatus("대기열 입장 중");
    const join = await apiPost<QueueJoinData>(
      "/v1/queue/join",
      { queue_key: `ticket:${eventId}` },
      { "X-User-Id": "1001" }
    );
    setQueueTicket(join.data.ticket);
    setQueuePosition(join.data.position);
    setQueueWaitSeconds(join.data.estimated_wait_seconds);
    setStatus("대기열 대기중");

    const poll = async () => {
      const result = await apiGet<QueueStatusData>(`/v1/queue/status?ticket=${encodeURIComponent(join.data.ticket)}`);
      setQueuePosition(result.data.position);
      setQueueWaitSeconds(result.data.estimated_wait_seconds);

      if (result.data.state === "EXPIRED") {
        resetQueue();
        setStatus("대기열 만료");
        setError("QUEUE_TOKEN_INVALID: 대기열 입장권이 만료되었습니다.");
        return;
      }
      if (result.data.state === "ADMITTED" && result.data.admit_token) {
        resetQueue();
        setStatus("입장 허용, CONFIRM 재시도");
        try {
          await attemptConfirm(result.data.admit_token);
        } catch (confirmError) {
          const err = toError(confirmError);
          setStatus("CONFIRM 실패");
          setError(`${err.code ?? "ERROR"}: ${err.message ?? "confirm 실패"}`);
        }
      }
    };

    queuePollRef.current = window.setInterval(() => {
      void poll().catch(() => {
        setStatus("대기열 대기중");
      });
    }, 2000);
    await poll();
  }

  async function hold() {
    resetQueue();
    setError(null);
    setOrderId(null);
    setExpiresAt(null);
    setStatus("HOLD 생성 중");
    try {
      const res = await attemptHold();
      setOrderId(res.data.order_id);
      setExpiresAt(res.data.expires_at);
      setStatus("HOLD 완료");
    } catch (e) {
      const err = toError(e);
      if (err.code === "QUEUE_REQUIRED") {
        await handleQueue().catch((queueError: unknown) => {
          const queueErr = toError(queueError);
          setError(`${queueErr.code ?? "ERROR"}: ${queueErr.message ?? "대기열 처리 실패"}`);
        });
        return;
      }
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "hold 실패"}`);
      if (err.code === "TICKET_SOLD_OUT") {
        setStatus("매진");
      } else {
        setStatus("HOLD 실패");
      }
    }
  }

  async function confirm() {
    if (!orderId || remainingSeconds === 0) return;
    setError(null);
    setStatus("CONFIRM 진행 중");
    try {
      await attemptConfirm();
    } catch (e) {
      const err = toError(e);
      if (err.code === "QUEUE_REQUIRED") {
        await handleConfirmQueue().catch((queueError: unknown) => {
          const queueErr = toError(queueError);
          setStatus("대기열 실패");
          setError(`${queueErr.code ?? "ERROR"}: ${queueErr.message ?? "대기열 처리 실패"}`);
        });
        return;
      }
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
      <h2>티켓 결제</h2>
      <p>상태: {status}</p>
      <p>수량: {quantity}</p>
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
        <button onClick={hold}>HOLD</button>
        <button disabled={!orderId || isExpired} onClick={confirm}>CONFIRM</button>
      </div>
      {isExpired && <p className="warning">HOLD가 만료되어 다시 생성이 필요합니다.</p>}
    </section>
  );
}
