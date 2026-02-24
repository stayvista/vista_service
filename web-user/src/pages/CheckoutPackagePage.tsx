import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";
import { verifyServerSession } from "../auth/serverSession";
import { toFriendlyCheckoutError, type CheckoutApiError } from "./checkoutErrorMessage";

type HoldResponse = {
  package_order_id: string;
  status: string;
  expires_at: string;
  components: {
    booking_id: string;
    ticket_order_id: string;
  };
};

type PackageDetail = {
  package_id: number;
  name: string;
  status: string;
  components: Array<{
    type: string;
    room_type_id?: number;
    event_id?: number;
    nights?: number;
    rooms?: number;
    quantity?: number;
  }>;
  price: {
    currency: string;
    amount_total: number;
  };
};

type QueueJoinData = { ticket: string; position: number; estimated_wait_seconds: number };
type QueueStatusData = {
  state: "WAITING" | "ADMITTED" | "EXPIRED";
  position: number;
  estimated_wait_seconds: number;
  admit_token: string | null;
};

type StatusTone = "neutral" | "info" | "success" | "warning" | "danger";
type StatusDescriptor = { title: string; description: string; tone: StatusTone };

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
  if (seconds === null) return "아직 패키지 임시 확보를 시작하지 않았습니다.";
  if (seconds === 0) return "임시 확보 시간이 만료되었습니다. 다시 진행해 주세요.";
  const minute = Math.floor(seconds / 60);
  const second = String(seconds % 60).padStart(2, "0");
  return `결제 가능 시간 ${minute}:${second} 남음`;
}

function describeStatus(status: string, hasError: boolean): StatusDescriptor {
  if (status.includes("재고 마감")) {
    return {
      title: "선택한 구성이 마감되었습니다",
      description: "다른 고객이 먼저 결제를 완료해 선택한 구성 일부가 마감되었습니다.",
      tone: "warning",
    };
  }
  if (status.includes("보유 시간 만료")) {
    return {
      title: "보유 시간이 만료되었습니다",
      description: "패키지 구성 상품을 다시 조회한 뒤 결제를 진행해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("결제 승인 실패")) {
    return {
      title: "결제 승인이 실패했습니다",
      description: "결제 정보를 확인한 뒤 다시 시도해 주세요.",
      tone: "danger",
    };
  }
  if (hasError || status.includes("실패")) {
    return {
      title: "패키지 결제를 완료하지 못했습니다",
      description: "일시적인 오류일 수 있습니다. 임시 확보부터 다시 진행하면 안전하게 재시도할 수 있습니다.",
      tone: "danger",
    };
  }
  if (status === "HOLD 완료") {
    return {
      title: "패키지 임시 확보가 완료되었습니다",
      description: "남은 시간 안에 결제를 확정하면 숙소와 티켓이 함께 예약됩니다.",
      tone: "success",
    };
  }
  if (status.includes("CONFIRM 진행")) {
    return {
      title: "패키지 결제 확정 중입니다",
      description: "구성 항목 재고와 결제 정보를 함께 검증하고 있습니다.",
      tone: "info",
    };
  }
  if (status.includes("대기열")) {
    return {
      title: "트래픽이 많아 순번 대기 중입니다",
      description: "순번이 되면 임시 확보 또는 결제 확정 단계가 자동으로 다시 진행됩니다.",
      tone: "info",
    };
  }
  if (status.includes("만료")) {
    return {
      title: "임시 확보 시간이 만료되었습니다",
      description: "패키지를 다시 임시 확보한 뒤 결제를 확정해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("생성 중")) {
    return {
      title: "패키지 임시 확보를 진행하고 있습니다",
      description: "숙소와 티켓 구성 항목을 함께 잠금 처리하고 있습니다.",
      tone: "info",
    };
  }
  return {
    title: "패키지 결제 준비가 완료되었습니다",
    description: "먼저 임시 확보를 진행한 뒤 결제를 확정해 주세요.",
    tone: "neutral",
  };
}

function componentLabel(type: string): string {
  if (type === "ACCOMMODATION") return "숙소";
  if (type === "TICKET") return "티켓";
  return type;
}

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
  const [item, setItem] = useState<PackageDetail | null>(null);
  const [itemError, setItemError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queuePollRef = useRef<number | null>(null);

  const packageId = params.get("package_id");
  const checkIn = params.get("check_in") ?? "2026-02-10";
  const checkOut = params.get("check_out") ?? "2026-02-12";
  const rooms = Math.max(1, Number(params.get("rooms") ?? "1"));
  const ticketQuantity = Math.max(1, Number(params.get("ticket_quantity") ?? "1"));
  const nights = useMemo(() => {
    const start = new Date(`${checkIn}T00:00:00`);
    const end = new Date(`${checkOut}T00:00:00`);
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 0;
    return Math.max(0, Math.round((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)));
  }, [checkIn, checkOut]);

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

  useEffect(() => {
    let mounted = true;
    if (!packageId) return undefined;
    setItemError(null);

    apiGet<PackageDetail>(`/v1/packages/${packageId}`)
      .then((res) => {
        if (!mounted) return;
        setItem(res.data);
      })
      .catch((e: unknown) => {
        if (!mounted) return;
        const err = e as CheckoutApiError;
        setItem(null);
        setItemError(`${err.code ?? "ERROR"}: ${err.message ?? "패키지 정보 조회 실패"}`);
      });

    return () => {
      mounted = false;
    };
  }, [packageId]);

  function queueKey() {
    return `package:${packageId ?? "unknown"}`;
  }

  function toError(value: unknown): CheckoutApiError {
    if (typeof value === "object" && value !== null) return value as CheckoutApiError;
    return {};
  }

  function isAuthError(value: CheckoutApiError): boolean {
    const code = (value.code ?? "").trim().toUpperCase();
    const message = (value.message ?? "").trim().toLowerCase();
    return (
      code.includes("AUTH") ||
      code.includes("UNAUTHORIZED") ||
      message.includes("unauthorized") ||
      message.includes("access token") ||
      message.includes("로그인")
    );
  }

  function moveToLogin() {
    const next = `${window.location.pathname}${window.location.search}`;
    navigate(`/login?next=${encodeURIComponent(next)}`);
  }

  async function ensureServerSession(): Promise<boolean> {
    const state = await verifyServerSession();
    if (state === "unauthorized") {
      setStatus("로그인 필요");
      setError("세션이 만료되어 다시 로그인해 주세요.");
      moveToLogin();
      return false;
    }
    return true;
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
      { queue_key: queueKey() }
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
        const friendly = toFriendlyCheckoutError("package", "queue", { code: "QUEUE_TOKEN_INVALID" });
        setStatus(friendly.status);
        setError(friendly.message);
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
    if (!packageId) throw { code: "VALIDATION_ERROR", message: "package_id is required" } as CheckoutApiError;
    return apiPost<HoldResponse>(
      `/v1/packages/${packageId}/holds`,
      { check_in: checkIn, check_out: checkOut, rooms, ticket_quantity: ticketQuantity },
      {
        "Idempotency-Key": crypto.randomUUID(),
        ...(queueToken ? { "Queue-Token": queueToken } : {}),
      }
    );
  }

  async function hold() {
    if (!(await ensureServerSession())) {
      return;
    }
    resetQueue();
    setError(null);
    setPackageOrderId(null);
    setBookingId(null);
    setTicketOrderId(null);
    setExpiresAt(null);
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
      if (isAuthError(err)) {
        setStatus("로그인 필요");
        setError("세션이 만료되어 다시 로그인해 주세요.");
        moveToLogin();
        return;
      }
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
          const friendly = toFriendlyCheckoutError("package", "queue", queueErr);
          setStatus(friendly.status);
          setError(friendly.message);
        });
        return;
      }
      const friendly = toFriendlyCheckoutError("package", "hold", err);
      setStatus(friendly.status);
      setError(friendly.message);
    }
  }

  async function attemptConfirm(queueToken?: string) {
    if (!packageId || !packageOrderId) {
      throw { code: "VALIDATION_ERROR", message: "package/order id is required" } as CheckoutApiError;
    }
    await apiPost(
      `/v1/packages/${packageId}/confirm`,
      { package_order_id: packageOrderId, payment_token: "paytok_test" },
      {
        "Idempotency-Key": crypto.randomUUID(),
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
    if (!(await ensureServerSession())) {
      return;
    }
    setError(null);
    setStatus("CONFIRM 진행 중");
    try {
      await attemptConfirm();
    } catch (e) {
      const err = toError(e);
      if (isAuthError(err)) {
        setStatus("로그인 필요");
        setError("세션이 만료되어 다시 로그인해 주세요.");
        moveToLogin();
        return;
      }
      if (err.code === "QUEUE_REQUIRED") {
        await runQueueFlow(async (admitToken) => {
          setStatus("입장 허용, CONFIRM 재시도");
          await attemptConfirm(admitToken);
        }).catch((queueError: unknown) => {
          const queueErr = toError(queueError);
          const friendly = toFriendlyCheckoutError("package", "queue", queueErr);
          setStatus(friendly.status);
          setError(friendly.message);
        });
        return;
      }
      const friendly = toFriendlyCheckoutError("package", "confirm", err);
      setStatus(friendly.status);
      setError(friendly.message);
    }
  }

  const isExpired = remainingSeconds === 0;
  const statusDescriptor = describeStatus(status, Boolean(error));

  return (
    <section className="page checkout-page">
      <header className="checkout-hero">
        <p className="page-kicker">PACKAGE CHECKOUT · DUAL INVENTORY LOCK</p>
        <h2>{item?.name ?? "패키지 결제 확인"}</h2>
        <p className="page-summary">
          숙소와 티켓 재고를 동시에 임시 확보한 뒤 한 번의 결제 확정으로 예약을 완료합니다.
        </p>
        <div className="chips">
          <span className="status-pill active">숙소+티켓 동시 확보</span>
          <span className="status-pill">부분 구매 자동 방지</span>
          <span className="status-pill">확정 즉시 예약 반영</span>
        </div>
      </header>

      <ul className="checkout-steps" aria-label="패키지 결제 단계">
        <li className="checkout-step completed">1. 여행 일정 확인</li>
        <li className={packageOrderId ? "checkout-step completed" : "checkout-step active"}>2. 패키지 임시 확보</li>
        <li className={packageOrderId && !isExpired ? "checkout-step active" : "checkout-step"}>3. 결제 확정</li>
      </ul>

      <div className="checkout-grid">
        <article className="checkout-card">
          <h3>예약 정보 확인</h3>
          <dl className="checkout-summary">
            <div>
              <dt>패키지</dt>
              <dd>{item?.name ?? `패키지 #${packageId ?? "-"}`}</dd>
            </div>
            <div>
              <dt>체크인</dt>
              <dd>{toIsoDateLabel(checkIn)}</dd>
            </div>
            <div>
              <dt>체크아웃</dt>
              <dd>{toIsoDateLabel(checkOut)}</dd>
            </div>
            <div>
              <dt>숙박/객실</dt>
              <dd>{nights}박 · {rooms}객실</dd>
            </div>
            <div>
              <dt>티켓 수량</dt>
              <dd>{ticketQuantity}매</dd>
            </div>
            <div>
              <dt>예상 결제 금액</dt>
              <dd>{(item?.price.amount_total ?? 0).toLocaleString()} {item?.price.currency ?? "KRW"}</dd>
            </div>
            {packageOrderId && (
              <div>
                <dt>패키지 주문 번호</dt>
                <dd>{packageOrderId}</dd>
              </div>
            )}
          </dl>

          <div className="queue-box">
            <p>결제 전 안내</p>
            <p>1. 임시 확보는 제한 시간 내에만 유효하며 만료 시 자동 반납됩니다.</p>
            <p>2. 확정 중 오류가 발생하면 서버에서 보상 처리되어 부분 구매 상태가 남지 않습니다.</p>
          </div>

          {item?.components && item.components.length > 0 && (
            <ul className="checkout-component-list">
              {item.components.map((component, idx) => (
                <li key={`${component.type}-${idx}`} className="checkout-component-item">
                  <p className="component-type">{component.type}</p>
                  <h4>{componentLabel(component.type)}</h4>
                  {component.room_type_id && <p>객실 타입 #{component.room_type_id}</p>}
                  {component.event_id && <p>이벤트 #{component.event_id}</p>}
                  {component.nights != null && <p>숙박 {component.nights}박</p>}
                  {component.rooms != null && <p>객실 {component.rooms}개</p>}
                  {component.quantity != null && <p>티켓 {component.quantity}매</p>}
                </li>
              ))}
            </ul>
          )}

          {itemError && <p className="notice warning">{itemError}</p>}
        </article>

        <article className="checkout-card">
          <h3>결제 진행 상태</h3>
          <div className={`checkout-status ${statusDescriptor.tone}`}>
            <strong>{statusDescriptor.title}</strong>
            <p>{statusDescriptor.description}</p>
          </div>
          <p className="checkout-timer">{toCountdownLabel(remainingSeconds)}</p>
          {expiresAt && <p className="checkout-expire">임시 확보 만료 시각: {toDateTimeLabel(expiresAt)}</p>}
          {packageOrderId && (
            <div className="queue-box checkout-queue">
              <p>패키지 주문: {packageOrderId}</p>
              <p>숙소 임시 예약: {bookingId ?? "-"}</p>
              <p>티켓 임시 주문: {ticketOrderId ?? "-"}</p>
            </div>
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
            <button onClick={hold}>{packageOrderId && !isExpired ? "패키지 다시 확보" : "패키지 임시 확보 시작"}</button>
            <button disabled={!packageOrderId || isExpired} onClick={confirm}>결제 확정하기</button>
          </div>
          <p className="checkout-note">
            결제 확정 단계에서 일부 구성 항목 실패가 발생하면 자동 보상되어 부분 구매 상태가 남지 않습니다.
          </p>
        </article>
      </div>

      {isExpired && <p className="notice warning">임시 확보 시간이 만료되어 패키지를 다시 확보해야 합니다.</p>}
    </section>
  );
}
