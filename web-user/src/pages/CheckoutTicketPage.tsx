import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
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
  if (seconds === 0) return "임시 확보 시간이 만료되었습니다. 다시 진행해 주세요.";
  const minute = Math.floor(seconds / 60);
  const second = String(seconds % 60).padStart(2, "0");
  return `임시 확보 종료까지 ${minute}:${second}`;
}

function describeStatus(status: string, hasError: boolean): StatusDescriptor {
  if (hasError || status.includes("실패")) {
    return {
      title: "결제 요청을 완료하지 못했습니다",
      description: "네트워크 상태를 확인한 뒤 임시 확보부터 다시 시도해 주세요.",
      tone: "danger",
    };
  }
  if (status === "HOLD 완료") {
    return {
      title: "좌석 임시 확보가 완료되었습니다",
      description: "남은 시간 안에 결제를 확정하면 티켓 발급 단계로 이동합니다.",
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
      title: "대기열 순번을 기다리고 있습니다",
      description: "순번이 되면 임시 확보 또는 확정이 자동으로 재시도됩니다.",
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
      description: "보통 몇 초 내에 처리되며 완료 후 결제 버튼이 활성화됩니다.",
      tone: "info",
    };
  }
  return {
    title: "결제 준비가 완료되었습니다",
    description: "먼저 좌석을 임시 확보한 후 결제를 확정해 주세요.",
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
    setAlternativeEvents([]);
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
        void loadAlternativeEvents();
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

  const isExpired = remainingSeconds === 0;
  const statusDescriptor = describeStatus(status, Boolean(error));

  return (
    <section className="page checkout-page">
      <header className="checkout-hero">
        <p className="page-kicker">INSTANT TICKET CHECKOUT</p>
        <h2>{product?.name ?? "티켓 결제 확인"}</h2>
        <p className="page-summary">
          선택한 회차를 임시 확보한 뒤 남은 시간 안에 결제를 완료하면 티켓 구매가 즉시 확정됩니다.
        </p>
      </header>

      <ul className="checkout-steps" aria-label="티켓 결제 단계">
        <li className={selectedEvent ? "checkout-step completed" : "checkout-step active"}>1. 회차 및 수량 확인</li>
        <li className={orderId ? "checkout-step completed" : "checkout-step active"}>2. 좌석 임시 확보</li>
        <li className={orderId && !isExpired ? "checkout-step active" : "checkout-step"}>3. 결제 확정</li>
      </ul>

      <div className="checkout-grid">
        <article className="checkout-card">
          <h3>구매 정보</h3>
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
          <h3>진행 상태</h3>
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
            <button onClick={hold}>{orderId && !isExpired ? "좌석 다시 임시 확보" : "좌석 임시 확보"}</button>
            <button disabled={!orderId || isExpired} onClick={confirm}>결제하고 예약 확정</button>
          </div>
          <p className="checkout-note">
            임시 확보 후 제한 시간이 지나면 좌석은 자동 반납됩니다. 이후에는 임시 확보를 다시 진행해 주세요.
          </p>
        </article>
      </div>

      {isExpired && <p className="notice warning">임시 확보 시간이 만료되어 좌석을 다시 확보해야 합니다.</p>}
    </section>
  );
}
