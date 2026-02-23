import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";
import { toFriendlyCheckoutError, type CheckoutApiError } from "./checkoutErrorMessage";

type QueueJoinData = { ticket: string; position: number; estimated_wait_seconds: number };
type QueueStatusData = {
  state: "WAITING" | "ADMITTED" | "EXPIRED";
  position: number;
  estimated_wait_seconds: number;
  admit_token: string | null;
};
type EventItem = {
  event_id: number;
  event_date: string;
  start_time: string;
  end_time?: string | null;
  total: number;
  hold: number;
  sold: number;
};
type TicketProduct = { product_id: number; name: string; category: string };
type StatusTone = "neutral" | "info" | "success" | "warning" | "danger";
type StatusDescriptor = { title: string; description: string; tone: StatusTone };

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

function toEventLabel(eventDate: string, startTime: string): string {
  const date = new Date(`${eventDate}T${startTime}`);
  if (Number.isNaN(date.getTime())) return `${eventDate} ${startTime.slice(0, 5)}`;
  return date.toLocaleString("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function toCountdownLabel(seconds: number | null): string {
  if (seconds === null) return "아직 좌석 임시 확보를 시작하지 않았습니다.";
  if (seconds === 0) return "임시 확보 시간이 만료되었습니다. 좌석을 다시 선택해 주세요.";
  const minute = Math.floor(seconds / 60);
  const second = String(seconds % 60).padStart(2, "0");
  return `결제 가능 시간 ${minute}:${second} 남음`;
}

function describeStatus(status: string, hasError: boolean): StatusDescriptor {
  if (status.includes("재고 마감")) {
    return {
      title: "선택한 회차가 마감되었습니다",
      description: "결제 직전 재고 재검증 과정에서 다른 고객이 먼저 결제를 완료했습니다. 다른 회차를 선택해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("보유 시간 만료")) {
    return {
      title: "결제 가능 시간이 만료되었습니다",
      description: "좌석을 다시 확보한 뒤 결제를 진행해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("결제 승인 실패")) {
    return {
      title: "결제 승인이 실패했습니다",
      description: "결제 수단 정보를 확인한 뒤 다시 시도해 주세요.",
      tone: "danger",
    };
  }
  if (hasError || status.includes("실패")) {
    return {
      title: "결제를 완료하지 못했습니다",
      description: "일시적인 오류일 수 있어요. 임시 확보부터 다시 진행하면 안전하게 재시도됩니다.",
      tone: "danger",
    };
  }
  if (status === "HOLD 완료") {
    return {
      title: "좌석 임시 확보가 완료되었습니다",
      description: "남은 시간 안에 결제를 확정하면 바우처가 즉시 발급됩니다.",
      tone: "success",
    };
  }
  if (status.includes("CONFIRM 진행")) {
    return {
      title: "결제 확정 처리 중입니다",
      description: "재고와 결제 토큰을 검증하고 있어 잠시만 기다려 주세요.",
      tone: "info",
    };
  }
  if (status.includes("대기열")) {
    return {
      title: "접속이 몰려 순번을 기다리고 있습니다",
      description: "순번이 되면 임시 확보 또는 결제 확정이 자동으로 다시 시도됩니다.",
      tone: "info",
    };
  }
  if (status.includes("매진")) {
    return {
      title: "선택한 회차가 매진되었습니다",
      description: "아래 대체 가능한 회차에서 즉시 예약 가능한 시간을 선택해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("만료")) {
    return {
      title: "임시 확보 시간이 만료되었습니다",
      description: "좌석을 다시 임시 확보한 뒤 결제를 확정해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("생성 중")) {
    return {
      title: "좌석 임시 확보를 진행하고 있습니다",
      description: "재고를 잠금 처리 중이며 완료되면 결제 버튼이 자동으로 활성화됩니다.",
      tone: "info",
    };
  }
  return {
    title: "결제 준비가 완료되었습니다",
    description: "좌석을 먼저 임시 확보한 뒤 결제를 확정해 주세요.",
    tone: "neutral",
  };
}

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
  const [eventCatalog, setEventCatalog] = useState<EventItem[]>([]);
  const [product, setProduct] = useState<TicketProduct | null>(null);
  const [alternativeEvents, setAlternativeEvents] = useState<EventItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const queuePollRef = useRef<number | null>(null);

  const eventId = Number(params.get("event_id") ?? "0");
  const productId = Number(params.get("product_id") ?? "0");
  const quantity = useMemo(
    () => Math.max(1, Number(params.get("quantity") ?? "1")),
    [params]
  );
  const totalAmount = 43000 * quantity;

  const selectedEvent = useMemo(
    () => eventCatalog.find((event) => event.event_id === eventId) ?? null,
    [eventCatalog, eventId]
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

  useEffect(() => {
    let mounted = true;
    if (productId <= 0) return undefined;

    const loadMeta = async () => {
      try {
        const [productsRes, eventsRes] = await Promise.all([
          apiGet<{ items: TicketProduct[] }>("/v1/tickets/products"),
          apiGet<{ items: EventItem[] }>(`/v1/tickets/events?product_id=${productId}`),
        ]);
        if (!mounted) return;
        const currentProduct = (productsRes.data.items ?? []).find((item) => item.product_id === productId) ?? null;
        setProduct(currentProduct);
        setEventCatalog(eventsRes.data.items ?? []);
      } catch {
        if (!mounted) return;
        setEventCatalog([]);
      }
    };

    void loadMeta();
    return () => {
      mounted = false;
    };
  }, [productId]);

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

  function resetQueue() {
    setQueueTicket(null);
    setQueuePosition(null);
    setQueueWaitSeconds(null);
    if (queuePollRef.current !== null) {
      window.clearInterval(queuePollRef.current);
      queuePollRef.current = null;
    }
  }

  async function loadAlternativeEvents() {
    let events = eventCatalog;
    if (events.length === 0 && productId > 0) {
      try {
        const res = await apiGet<{ items: EventItem[] }>(`/v1/tickets/events?product_id=${productId}`);
        events = res.data.items ?? [];
        setEventCatalog(events);
      } catch {
        setAlternativeEvents([]);
        return;
      }
    }
    const alternatives = events
      .filter((event) => event.event_id !== eventId)
      .filter((event) => (event.total - event.hold - event.sold) > 0)
      .slice(0, 4);
    setAlternativeEvents(alternatives);
  }

  async function attemptHold(queueToken?: string) {
    return apiPost<{ order_id: string; expires_at: string }>(
      "/v1/tickets/orders/holds",
      {
        event_id: eventId,
        quantity,
        price: { currency: "KRW", amount_total: totalAmount },
      },
      {
        "Idempotency-Key": crypto.randomUUID(),
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
      { queue_key: `ticket:${eventId}` }
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
        const friendly = toFriendlyCheckoutError("ticket", "queue", { code: "QUEUE_TOKEN_INVALID" });
        setStatus(friendly.status);
        setError(friendly.message);
        return;
      }
      if (result.data.state === "ADMITTED" && result.data.admit_token) {
        resetQueue();
        setStatus("입장 허용, HOLD 재시도");
        try {
          const hold = await attemptHold(result.data.admit_token);
          setOrderId(hold.data.order_id);
          setExpiresAt(hold.data.expires_at);
          setStatus("HOLD 완료");
        } catch (holdError) {
          const err = toError(holdError);
          if (isAuthError(err)) {
            setStatus("로그인 필요");
            setError("세션이 만료되어 다시 로그인해 주세요.");
            moveToLogin();
            return;
          }
          const friendly = toFriendlyCheckoutError("ticket", "hold", err);
          setStatus(friendly.status);
          setError(friendly.message);
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

  async function handleConfirmQueue() {
    setStatus("대기열 입장 중");
    const join = await apiPost<QueueJoinData>(
      "/v1/queue/join",
      { queue_key: `ticket:${eventId}` }
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
        const friendly = toFriendlyCheckoutError("ticket", "queue", { code: "QUEUE_TOKEN_INVALID" });
        setStatus(friendly.status);
        setError(friendly.message);
        return;
      }
      if (result.data.state === "ADMITTED" && result.data.admit_token) {
        resetQueue();
        setStatus("입장 허용, CONFIRM 재시도");
        try {
          await attemptConfirm(result.data.admit_token);
        } catch (confirmError) {
          const err = toError(confirmError);
          if (isAuthError(err)) {
            setStatus("로그인 필요");
            setError("세션이 만료되어 다시 로그인해 주세요.");
            moveToLogin();
            return;
          }
          const friendly = toFriendlyCheckoutError("ticket", "confirm", err);
          const soldOut = new Set(["TICKET_SOLD_OUT", "ORDER_STATE_CONFLICT", "INVENTORY_INVARIANT_VIOLATION"]);
          if (soldOut.has(err.code ?? "")) {
            setStatus("매진");
            setError(friendly.message);
            void loadAlternativeEvents();
          } else {
            setStatus(friendly.status);
            setError(friendly.message);
          }
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
    setAlternativeEvents([]);
    setStatus("HOLD 생성 중");
    try {
      const res = await attemptHold();
      setOrderId(res.data.order_id);
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
        await handleQueue().catch((queueError: unknown) => {
          const queueErr = toError(queueError);
          const friendly = toFriendlyCheckoutError("ticket", "queue", queueErr);
          setStatus(friendly.status);
          setError(friendly.message);
        });
        return;
      }
      const friendly = toFriendlyCheckoutError("ticket", "hold", err);
      setError(friendly.message);
      if (err.code === "TICKET_SOLD_OUT") {
        setStatus("매진");
        void loadAlternativeEvents();
      } else {
        setStatus(friendly.status);
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
      if (isAuthError(err)) {
        setStatus("로그인 필요");
        setError("세션이 만료되어 다시 로그인해 주세요.");
        moveToLogin();
        return;
      }
      if (err.code === "QUEUE_REQUIRED") {
        await handleConfirmQueue().catch((queueError: unknown) => {
          const queueErr = toError(queueError);
          const friendly = toFriendlyCheckoutError("ticket", "queue", queueErr);
          setStatus(friendly.status);
          setError(friendly.message);
        });
        return;
      }
      const friendly = toFriendlyCheckoutError("ticket", "confirm", err);
      const soldOut = new Set(["TICKET_SOLD_OUT", "ORDER_STATE_CONFLICT", "INVENTORY_INVARIANT_VIOLATION"]);
      if (soldOut.has(err.code ?? "")) {
        setStatus("매진");
        setError(friendly.message);
        void loadAlternativeEvents();
      } else {
        setStatus(friendly.status);
        setError(friendly.message);
      }
    }
  }

  const isExpired = remainingSeconds === 0;
  const statusDescriptor = describeStatus(status, Boolean(error));

  return (
    <section className="page checkout-page">
      <header className="checkout-hero">
        <p className="page-kicker">SECURE TICKET CHECKOUT · HOLD FIRST</p>
        <h2>{product?.name ?? "티켓 결제 확인"}</h2>
        <p className="page-summary">
          선택한 회차를 먼저 임시 확보하고 제한 시간 안에 결제를 완료하면 예약이 확정됩니다.
        </p>
        <div className="chips">
          <span className="status-pill active">실시간 재고 잠금</span>
          <span className="status-pill">만료 시 자동 반납</span>
          <span className="status-pill">확정 즉시 바우처 발급</span>
        </div>
      </header>

      <ul className="checkout-steps" aria-label="티켓 결제 단계">
        <li className={selectedEvent ? "checkout-step completed" : "checkout-step active"}>1. 회차 및 수량 확인</li>
        <li className={orderId ? "checkout-step completed" : "checkout-step active"}>2. 좌석 임시 확보</li>
        <li className={orderId && !isExpired ? "checkout-step active" : "checkout-step"}>3. 결제 확정</li>
      </ul>

      <div className="checkout-grid">
        <article className="checkout-card">
          <h3>예약 정보 확인</h3>
          <dl className="checkout-summary">
            <div>
              <dt>상품명</dt>
              <dd>{product?.name ?? `상품 #${productId || "-"}`}</dd>
            </div>
            <div>
              <dt>카테고리</dt>
              <dd>{product?.category ?? "TICKET"}</dd>
            </div>
            <div>
              <dt>선택 회차</dt>
              <dd>{selectedEvent ? toEventLabel(selectedEvent.event_date, selectedEvent.start_time) : `회차 #${eventId}`}</dd>
            </div>
            <div>
              <dt>수량</dt>
              <dd>{quantity}매</dd>
            </div>
            <div>
              <dt>예상 결제 금액</dt>
              <dd>{totalAmount.toLocaleString()} KRW</dd>
            </div>
            {orderId && (
              <div>
                <dt>임시 주문 번호</dt>
                <dd>{orderId}</dd>
              </div>
            )}
          </dl>

          <div className="queue-box">
            <p>결제 전 안내</p>
            <p>1. 임시 확보 후 제한 시간이 지나면 좌석은 자동 반납됩니다.</p>
            <p>2. 결제 확정이 완료되면 바우처 목록에서 QR을 확인할 수 있습니다.</p>
          </div>

          {alternativeEvents.length > 0 && (
            <section className="checkout-alternatives">
              <p className="panel-note">선택한 회차가 매진되어, 같은 상품의 예약 가능한 시간대를 추천합니다.</p>
              <ul className="card-list checkout-alt-list">
                {alternativeEvents.map((event) => (
                  <li key={event.event_id} className="card">
                    <p className="slot-datetime">{toEventLabel(event.event_date, event.start_time)}</p>
                    <p className="product-copy">잔여 {Math.max(0, event.total - event.hold - event.sold)}석</p>
                    <Link className="inline-cta" to={`/checkout/ticket?product_id=${productId}&event_id=${event.event_id}&quantity=${quantity}`}>
                      이 시간대로 변경
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </article>

        <article className="checkout-card">
          <h3>결제 진행 상태</h3>
          <div className={`checkout-status ${statusDescriptor.tone}`}>
            <strong>{statusDescriptor.title}</strong>
            <p>{statusDescriptor.description}</p>
          </div>
          <p className="checkout-timer">{toCountdownLabel(remainingSeconds)}</p>
          {expiresAt && <p className="checkout-expire">임시 확보 만료 시각: {toDateTimeLabel(expiresAt)}</p>}
          {queueTicket && (
            <div className="queue-box checkout-queue">
              <p>대기 번호: {queueTicket}</p>
              <p>현재 순번: {queuePosition ?? "-"}</p>
              <p>예상 대기 시간: {queueWaitSeconds ?? "-"}초</p>
            </div>
          )}
          {error && <p className="notice error">{error}</p>}
          <div className="actions checkout-actions">
            <button onClick={hold}>{orderId && !isExpired ? "좌석 다시 확보" : "좌석 임시 확보 시작"}</button>
            <button disabled={!orderId || isExpired} onClick={confirm}>결제 확정하기</button>
          </div>
          <p className="checkout-note">
            결제 확정 단계에서 오류가 발생하면 상태가 업데이트되며, 동일 좌석은 다시 임시 확보 후 재시도할 수 있습니다.
          </p>
        </article>
      </div>

      {isExpired && <p className="notice warning">임시 확보 시간이 만료되었습니다. 좌석 임시 확보를 다시 진행해 주세요.</p>}
    </section>
  );
}
