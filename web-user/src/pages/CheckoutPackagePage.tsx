import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";

type HoldResponse = {
  package_order_id: string;
  status: string;
  expires_at: string;
  components: {
    booking_id: string;
    ticket_order_id: string;
  };
};

type ApiError = {
  code?: string;
  message?: string;
};

type QueueJoinData = { ticket: string; position: number; estimated_wait_seconds: number };
type QueueStatusData = {
  state: "WAITING" | "ADMITTED" | "EXPIRED";
  position: number;
  estimated_wait_seconds: number;
  admit_token: string | null;
};

export function CheckoutPackagePage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [packageOrderId, setPackageOrderId] = useState<string | null>(null);
  const [bookingId, setBookingId] = useState<string | null>(null);
  const [ticketOrderId, setTicketOrderId] = useState<string | null>(null);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);
  const [status, setStatus] = useState("대기");
  const [queueTicket, setQueueTicket] = useState<string | null>(null);
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueWaitSeconds, setQueueWaitSeconds] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queuePollRef = useRef<number | null>(null);

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

  function queueKey() {
    const packageId = params.get("package_id") ?? "unknown";
    return `package:${packageId}`;
  }

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

  async function runQueueFlow(onAdmitted: (admitToken: string) => Promise<void>) {
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
        await onAdmitted(result.data.admit_token);
      }
    };

    queuePollRef.current = window.setInterval(() => {
      void poll().catch(() => {
        setStatus("대기열 대기중");
      });
    }, 2000);
    await poll();
  }

  async function attemptHold(queueToken?: string) {
    const packageId = params.get("package_id");
    if (!packageId) throw { code: "VALIDATION_ERROR", message: "package_id is required" } as ApiError;
    return apiPost<HoldResponse>(
      `/v1/packages/${packageId}/holds`,
      { check_in: "2026-02-10", check_out: "2026-02-12", rooms: 1, ticket_quantity: 1 },
      {
        "Idempotency-Key": crypto.randomUUID(),
        "X-User-Id": "1001",
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
  }

  async function hold() {
    resetQueue();
    setError(null);
    setStatus("HOLD 생성 중");
    try {
      const res = await attemptHold();
      setPackageOrderId(res.data.package_order_id);
      setBookingId(res.data.components.booking_id);
      setTicketOrderId(res.data.components.ticket_order_id);
      setExpiresAt(res.data.expires_at);
      setStatus("HOLD 완료");
    } catch (e) {
      const err = toError(e);
      if (err.code === "QUEUE_REQUIRED") {
        await runQueueFlow(async (admitToken) => {
          setStatus("입장 허용, HOLD 재시도");
          const res = await attemptHold(admitToken);
          setPackageOrderId(res.data.package_order_id);
          setBookingId(res.data.components.booking_id);
          setTicketOrderId(res.data.components.ticket_order_id);
          setExpiresAt(res.data.expires_at);
          setStatus("HOLD 완료");
        }).catch((queueError: unknown) => {
          const queueErr = toError(queueError);
          setStatus("대기열 실패");
          setError(`${queueErr.code ?? "ERROR"}: ${queueErr.message ?? "queue 처리 실패"}`);
        });
        return;
      }
      setStatus("HOLD 실패");
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "패키지 hold 실패"}`);
    }
  }

  async function attemptConfirm(queueToken?: string) {
    const packageId = params.get("package_id");
    if (!packageId || !packageOrderId) {
      throw { code: "VALIDATION_ERROR", message: "package/order id is required" } as ApiError;
    }
    await apiPost(
      `/v1/packages/${packageId}/confirm`,
      { package_order_id: packageOrderId, payment_token: "paytok_test" },
      {
        "Idempotency-Key": crypto.randomUUID(),
        "X-User-Id": "1001",
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
    const done = new URLSearchParams({
      type: "package",
      package_order_id: packageOrderId,
      ...(bookingId ? { booking_id: bookingId } : {}),
      ...(ticketOrderId ? { order_id: ticketOrderId } : {}),
    });
    navigate(`/booking/complete?${done.toString()}`);
  }

  async function confirm() {
    if (!packageOrderId || remainingSeconds === 0) return;
    setError(null);
    setStatus("CONFIRM 진행 중");
    try {
      await attemptConfirm();
    } catch (e) {
      const err = toError(e);
      if (err.code === "QUEUE_REQUIRED") {
        await runQueueFlow(async (admitToken) => {
          setStatus("입장 허용, CONFIRM 재시도");
          await attemptConfirm(admitToken);
        }).catch((queueError: unknown) => {
          const queueErr = toError(queueError);
          setStatus("대기열 실패");
          setError(`${queueErr.code ?? "ERROR"}: ${queueErr.message ?? "queue 처리 실패"}`);
        });
        return;
      }
      setStatus("CONFIRM 실패");
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "패키지 confirm 실패"}`);
    }
  }

  const countdownText = remainingSeconds === null
    ? "HOLD 생성 전"
    : `${Math.floor(remainingSeconds / 60)}:${String(remainingSeconds % 60).padStart(2, "0")}`;
  const isExpired = remainingSeconds === 0;

  return (
    <section className="page">
      <h2>패키지 결제</h2>
      <p>상태: {status}</p>
      <p>남은 시간: {countdownText}</p>
      {expiresAt && <p>만료시각: {expiresAt}</p>}
      {packageOrderId && (
        <div className="queue-box">
          <p>패키지 주문: {packageOrderId}</p>
          <p>숙소 HOLD: {bookingId}</p>
          <p>티켓 HOLD: {ticketOrderId}</p>
        </div>
      )}
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
        <button disabled={!packageOrderId || isExpired} onClick={confirm}>CONFIRM</button>
      </div>
      <p className="warning">확정 실패 시 부분 구매가 남지 않도록 서버에서 보상 처리됩니다.</p>
    </section>
  );
}
