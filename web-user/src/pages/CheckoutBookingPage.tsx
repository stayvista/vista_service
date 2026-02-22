import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";
import { toFriendlyCheckoutError, type CheckoutApiError } from "./checkoutErrorMessage";

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

type StatusTone = "neutral" | "info" | "success" | "warning" | "danger";

type StatusDescriptor = {
  title: string;
  description: string;
  tone: StatusTone;
};

function toIsoDateLabel(value: string): string {
  const date = new Date(`${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("ko-KR", { month: "long", day: "numeric", weekday: "short" });
}

function toDateTimeLabel(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("ko-KR", {
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function toCountdownLabel(seconds: number | null): string {
  if (seconds === null) return "아직 객실 임시 확보 전입니다.";
  if (seconds === 0) return "임시 확보 시간이 만료되었습니다. 다시 시도해 주세요.";
  const minute = Math.floor(seconds / 60);
  const second = String(seconds % 60).padStart(2, "0");
  return `임시 확보 종료까지 ${minute}:${second}`;
}

function describeStatus(status: string, hasError: boolean): StatusDescriptor {
  if (status.includes("재고 마감")) {
    return {
      title: "선택한 객실/요금이 마감되었습니다",
      description: "결제 직전 재고 재검증 과정에서 다른 고객이 먼저 결제를 완료했습니다. 다른 객실 또는 날짜로 다시 진행해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("보유 시간 만료")) {
    return {
      title: "객실 보유 시간이 만료되었습니다",
      description: "다시 검색 후 객실을 선택해 예약을 진행해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("결제 승인 실패")) {
    return {
      title: "결제 승인이 실패했습니다",
      description: "결제 수단을 확인한 뒤 다시 시도해 주세요.",
      tone: "danger",
    };
  }
  if (hasError || status.includes("실패")) {
    return {
      title: "요청 처리에 문제가 발생했어요",
      description: "잠시 후 다시 시도하거나 임시 확보를 다시 진행해 주세요.",
      tone: "danger",
    };
  }
  if (status === "HOLD 완료") {
    return {
      title: "객실 임시 확보가 완료되었습니다",
      description: "남은 시간 안에 예약을 확정하면 객실이 최종 예약됩니다.",
      tone: "success",
    };
  }
  if (status.includes("CONFIRM 진행")) {
    return {
      title: "예약 확정 처리 중입니다",
      description: "결제 검증과 좌석 동기화를 진행하고 있습니다.",
      tone: "info",
    };
  }
  if (status.includes("대기열")) {
    return {
      title: "접속량이 많아 순번 대기 중입니다",
      description: "순번이 되면 자동으로 다음 단계가 진행됩니다.",
      tone: "info",
    };
  }
  if (status.includes("만료")) {
    return {
      title: "임시 확보 시간이 만료되었습니다",
      description: "객실을 다시 임시 확보한 뒤 예약을 확정해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("생성 중")) {
    return {
      title: "객실 임시 확보를 진행하고 있습니다",
      description: "잠시만 기다려 주세요. 보통 몇 초 내 완료됩니다.",
      tone: "info",
    };
  }
  return {
    title: "예약 준비가 완료되었습니다",
    description: "먼저 객실을 임시 확보한 뒤 예약을 확정해 주세요.",
    tone: "neutral",
  };
}

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

  const checkIn = params.get("check_in") ?? "2026-02-10";
  const checkOut = params.get("check_out") ?? "2026-02-12";
  const rooms = Math.max(1, Number(params.get("rooms") ?? "1"));
  const adults = Math.max(1, Number(params.get("adults") ?? "2"));
  const children = Math.max(0, Number(params.get("children") ?? "0"));

  const nights = useMemo(() => {
    const start = new Date(`${checkIn}T00:00:00`);
    const end = new Date(`${checkOut}T00:00:00`);
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 0;
    return Math.max(0, Math.round((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)));
  }, [checkIn, checkOut]);

  const holdBody = useMemo(() => ({
    room_type_id: Number(params.get("room_type_id") ?? "0"),
    check_in: checkIn,
    check_out: checkOut,
    rooms,
    guests: { adults, children },
    price: { currency: "KRW", amount_total: 120000 * rooms },
  }), [adults, checkIn, checkOut, children, params, rooms]);

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

  function toApiError(value: unknown): CheckoutApiError {
    if (typeof value === "object" && value !== null) {
      return value as CheckoutApiError;
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
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
  }

  async function attemptConfirm(queueToken?: string) {
    if (!bookingId || remainingSeconds === 0) return;
    await apiPost(
      `/v1/bookings/${bookingId}/confirm`,
      { payment_method: "CARD", payment_token: "paytok_test", agree_terms: true },
      {
        "Idempotency-Key": crypto.randomUUID(),
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
    const done = new URLSearchParams({
      type: "booking",
      booking_id: bookingId,
    });
    navigate(`/booking/complete?${done.toString()}`);
  }

  async function handleQueueFlow() {
    setStatus("대기열 입장 중");
    const join = await apiPost<QueueJoinData>(
      "/v1/queue/join",
      { queue_key: queueKey() }
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
        const friendly = toFriendlyCheckoutError("booking", "queue", { code: "QUEUE_TOKEN_INVALID" });
        setStatus(friendly.status);
        setError(friendly.message);
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
          const friendly = toFriendlyCheckoutError("booking", "hold", err);
          setStatus(friendly.status);
          setError(friendly.message);
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

  async function handleConfirmQueueFlow() {
    setStatus("대기열 입장 중");
    const join = await apiPost<QueueJoinData>(
      "/v1/queue/join",
      { queue_key: queueKey() }
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
        const friendly = toFriendlyCheckoutError("booking", "queue", { code: "QUEUE_TOKEN_INVALID" });
        setStatus(friendly.status);
        setError(friendly.message);
        return;
      }

      if (statusResult.data.state === "ADMITTED" && statusResult.data.admit_token) {
        resetQueueState();
        setStatus("입장 허용, CONFIRM 재시도");
        try {
          await attemptConfirm(statusResult.data.admit_token);
        } catch (confirmError) {
          const err = toApiError(confirmError);
          const friendly = toFriendlyCheckoutError("booking", "confirm", err);
          setStatus(friendly.status);
          setError(friendly.message);
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
          const friendly = toFriendlyCheckoutError("booking", "queue", queueErr);
          setStatus(friendly.status);
          setError(friendly.message);
        });
        return;
      }
      const friendly = toFriendlyCheckoutError("booking", "hold", err);
      setStatus(friendly.status);
      setError(friendly.message);
    }
  }

  async function confirm() {
    if (!bookingId || remainingSeconds === 0) return;
    setError(null);
    setStatus("CONFIRM 진행 중");
    try {
      await attemptConfirm();
    } catch (e) {
      const err = toApiError(e);
      if (err.code === "QUEUE_REQUIRED") {
        await handleConfirmQueueFlow().catch((queueError: unknown) => {
          const queueErr = toApiError(queueError);
          const friendly = toFriendlyCheckoutError("booking", "queue", queueErr);
          setStatus(friendly.status);
          setError(friendly.message);
        });
        return;
      }
      const friendly = toFriendlyCheckoutError("booking", "confirm", err);
      setStatus(friendly.status);
      setError(friendly.message);
    }
  }

  const countdownText = remainingSeconds === null
    ? "HOLD 생성 전"
    : `${Math.floor(remainingSeconds / 60)}:${String(remainingSeconds % 60).padStart(2, "0")}`;

  const isExpired = remainingSeconds === 0;
  const statusDescriptor = describeStatus(status, Boolean(error));

  return (
    <section className="page checkout-page">
      <header className="checkout-hero">
        <p className="page-kicker">SECURE BOOKING CHECKOUT</p>
        <h2>숙소 예약 확인</h2>
        <p className="page-summary">
          객실을 먼저 임시 확보한 뒤 남은 시간 안에 예약을 확정하면 안전하게 예약이 완료됩니다.
        </p>
      </header>

      <ul className="checkout-steps" aria-label="예약 진행 단계">
        <li className={bookingId ? "checkout-step completed" : "checkout-step active"}>
          1. 객실 임시 확보
        </li>
        <li className={bookingId && !isExpired ? "checkout-step active" : "checkout-step"}>
          2. 예약 확정
        </li>
      </ul>

      <div className="checkout-grid">
        <article className="checkout-card">
          <h3>예약 정보</h3>
          <dl className="checkout-summary">
            <div>
              <dt>객실 코드</dt>
              <dd>#{holdBody.room_type_id}</dd>
            </div>
            <div>
              <dt>체크인</dt>
              <dd>{toIsoDateLabel(holdBody.check_in)}</dd>
            </div>
            <div>
              <dt>체크아웃</dt>
              <dd>{toIsoDateLabel(holdBody.check_out)}</dd>
            </div>
            <div>
              <dt>숙박 일수</dt>
              <dd>{nights}박</dd>
            </div>
            <div>
              <dt>인원</dt>
              <dd>성인 {holdBody.guests.adults} · 어린이 {holdBody.guests.children}</dd>
            </div>
            <div>
              <dt>객실 수</dt>
              <dd>{holdBody.rooms}개</dd>
            </div>
            <div>
              <dt>예상 결제 금액</dt>
              <dd>{holdBody.price.amount_total.toLocaleString()} KRW</dd>
            </div>
          </dl>
        </article>

        <article className="checkout-card">
          <h3>진행 상태</h3>
          <div className={`checkout-status ${statusDescriptor.tone}`}>
            <strong>{statusDescriptor.title}</strong>
            <p>{statusDescriptor.description}</p>
          </div>
          <p className="checkout-timer">{toCountdownLabel(remainingSeconds)}</p>
          {expiresAt && (
            <p className="checkout-expire">임시 확보 만료 시각: {toDateTimeLabel(expiresAt)}</p>
          )}
          {queueTicket && (
            <div className="queue-box checkout-queue">
              <p>대기 번호: {queueTicket}</p>
              <p>현재 순번: {queuePosition ?? "-"}</p>
              <p>예상 대기 시간: {queueWaitSeconds ?? "-"}초</p>
            </div>
          )}
          {error && <p className="notice error">{error}</p>}
          <div className="actions checkout-actions">
            <button onClick={createHold}>
              {bookingId && !isExpired ? "객실 다시 임시 확보" : "객실 임시 확보"}
            </button>
            <button disabled={!bookingId || isExpired} onClick={confirm}>
              예약 확정하기
            </button>
          </div>
          <p className="checkout-note">
            임시 확보 후 시간이 만료되면 객실이 자동으로 반환되며, 다시 임시 확보 후 확정이 필요합니다.
          </p>
        </article>
      </div>

      {isExpired && <p className="notice warning">임시 확보 시간이 만료되어 객실을 다시 확보해야 합니다.</p>}
    </section>
  );
}
