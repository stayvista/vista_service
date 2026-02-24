import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";
import { verifyServerSession } from "../auth/serverSession";
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
  if (seconds === null) return "아직 예약 진행 전입니다.";
  if (seconds === 0) return "진행 시간이 만료되었습니다. 다시 시도해 주세요.";
  const minute = Math.floor(seconds / 60);
  const second = String(seconds % 60).padStart(2, "0");
  return `현재 조건 유지 종료까지 ${minute}:${second}`;
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
      description: "잠시 후 다시 시도하거나 예약을 다시 진행해 주세요.",
      tone: "danger",
    };
  }
  if (status.includes("예약 준비 완료")) {
    return {
      title: "예약 진행 준비가 완료되었습니다",
      description: "남은 시간 안에 결제를 완료하면 예약이 확정됩니다.",
      tone: "success",
    };
  }
  if (status.includes("결제 처리 중")) {
    return {
      title: "결제 처리 중입니다",
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
      title: "진행 시간이 만료되었습니다",
      description: "객실을 다시 선택한 뒤 예약을 진행해 주세요.",
      tone: "warning",
    };
  }
  if (status.includes("조건 확인 중")) {
    return {
      title: "예약 진행 준비 중입니다",
      description: "잠시만 기다려 주세요. 보통 몇 초 내 완료됩니다.",
      tone: "info",
    };
  }
  return {
    title: "예약 준비가 완료되었습니다",
    description: "고객 정보와 결제 정보를 입력한 뒤 지금 예약하기를 눌러 주세요.",
    tone: "neutral",
  };
}

export function CheckoutBookingPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState("대기");
  const [bookingId, setBookingId] = useState<string | null>(() => params.get("booking_id"));
  const [expiresAt, setExpiresAt] = useState<string | null>(() => params.get("expires_at"));
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);
  const [queueTicket, setQueueTicket] = useState<string | null>(null);
  const [queuePosition, setQueuePosition] = useState<number | null>(null);
  const [queueWaitSeconds, setQueueWaitSeconds] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queuePollRef = useRef<number | null>(null);
  const autoPrepareRef = useRef(false);

  const [paymentTiming, setPaymentTiming] = useState<"pay_later" | "pay_now">("pay_later");
  const [leadFirstName, setLeadFirstName] = useState("SEUNGYOON");
  const [leadLastName, setLeadLastName] = useState("KIM");
  const [email, setEmail] = useState("neptuner25@gmail.com");
  const [country, setCountry] = useState("대한민국");
  const [phoneCountryCode, setPhoneCountryCode] = useState("+82");
  const [phoneNumber, setPhoneNumber] = useState("1092922495");
  const [arrivalTime, setArrivalTime] = useState("미정");
  const [specialHighFloor, setSpecialHighFloor] = useState(false);
  const [specialQuietRoom, setSpecialQuietRoom] = useState(false);
  const [specialParking, setSpecialParking] = useState(false);
  const [specialLateArrival, setSpecialLateArrival] = useState(false);
  const [specialNote, setSpecialNote] = useState("");
  const [agreeAll, setAgreeAll] = useState(false);
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreePrivacy, setAgreePrivacy] = useState(false);
  const [agreeThirdParty, setAgreeThirdParty] = useState(false);

  const checkIn = params.get("check_in") ?? "2026-02-10";
  const checkOut = params.get("check_out") ?? "2026-02-12";
  const rooms = Math.max(1, Number(params.get("rooms") ?? "1"));
  const adults = Math.max(1, Number(params.get("adults") ?? "2"));
  const children = Math.max(0, Number(params.get("children") ?? "0"));
  const propertyId = Math.max(0, Number(params.get("property_id") ?? "0"));

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

  const propertyName = params.get("property_name") ?? "선택한 숙소";
  const roomName = params.get("room_name") ?? `스탠다드 객실 #${holdBody.room_type_id}`;
  const reviewScore = Number(params.get("rating") ?? "8.8");
  const reviewCount = Number(params.get("review_count") ?? "8688");
  const listPrice = Math.round(holdBody.price.amount_total * 1.23);
  const couponDiscount = Math.min(Math.round(holdBody.price.amount_total * 0.19), 34305);
  const taxes = Math.round((holdBody.price.amount_total - couponDiscount) * 0.1);
  const grandTotal = holdBody.price.amount_total - couponDiscount + taxes;
  const canSubmit =
    leadFirstName.trim().length > 0 &&
    leadLastName.trim().length > 0 &&
    email.trim().length > 0 &&
    country.trim().length > 0 &&
    phoneNumber.trim().length > 0 &&
    agreeTerms &&
    agreePrivacy &&
    agreeThirdParty;

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
        setStatus("진행 시간 만료");
      }
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [expiresAt]);

  useEffect(() => {
    setAgreeAll(agreeTerms && agreePrivacy && agreeThirdParty);
  }, [agreeTerms, agreePrivacy, agreeThirdParty]);

  useEffect(() => {
    if (autoPrepareRef.current) return;
    if (bookingId) {
      autoPrepareRef.current = true;
      setStatus("예약 준비 완료");
      return;
    }
    if (holdBody.room_type_id <= 0) return;
    autoPrepareRef.current = true;
    void createHold();
  }, [bookingId, holdBody.room_type_id]);

  function toApiError(value: unknown): CheckoutApiError {
    if (typeof value === "object" && value !== null) {
      return value as CheckoutApiError;
    }
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

  function toggleAgreeAll(next: boolean) {
    setAgreeAll(next);
    setAgreeTerms(next);
    setAgreePrivacy(next);
    setAgreeThirdParty(next);
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
        setStatus("입장 허용, 예약 재시도");
        try {
          const hold = await attemptHold(statusResult.data.admit_token);
          setBookingId(hold.data.booking_id);
          setExpiresAt(hold.data.expires_at);
          setStatus("예약 준비 완료");
        } catch (holdError) {
          const err = toApiError(holdError);
          if (isAuthError(err)) {
            setStatus("로그인 필요");
            setError("세션이 만료되어 다시 로그인해 주세요.");
            moveToLogin();
            return;
          }
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
        setStatus("입장 허용, 결제 재시도");
        try {
          await attemptConfirm(statusResult.data.admit_token);
        } catch (confirmError) {
          const err = toApiError(confirmError);
          if (isAuthError(err)) {
            setStatus("로그인 필요");
            setError("세션이 만료되어 다시 로그인해 주세요.");
            moveToLogin();
            return;
          }
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
    if (!(await ensureServerSession())) {
      return;
    }
    resetQueueState();
    setError(null);
    setBookingId(null);
    setExpiresAt(null);
    setStatus("예약 조건 확인 중");
    try {
      const response = await attemptHold();
      setBookingId(response.data.booking_id);
      setExpiresAt(response.data.expires_at);
      setStatus("예약 준비 완료");
    } catch (e) {
      const err = toApiError(e);
      if (isAuthError(err)) {
        setStatus("로그인 필요");
        setError("세션이 만료되어 다시 로그인해 주세요.");
        moveToLogin();
        return;
      }
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
    if (!(await ensureServerSession())) {
      return;
    }
    setError(null);
    setStatus("결제 처리 중");
    try {
      await attemptConfirm();
    } catch (e) {
      const err = toApiError(e);
      if (isAuthError(err)) {
        setStatus("로그인 필요");
        setError("세션이 만료되어 다시 로그인해 주세요.");
        moveToLogin();
        return;
      }
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

  async function handlePrimaryAction() {
    if (!canSubmit) return;
    if (!bookingId || isExpired) {
      await createHold();
      return;
    }
    await confirm();
  }

  const countdownText = remainingSeconds === null
    ? "--:--"
    : `${Math.floor(remainingSeconds / 60)}:${String(remainingSeconds % 60).padStart(2, "0")}`;

  const isExpired = remainingSeconds === 0;
  const statusDescriptor = describeStatus(status, Boolean(error));
  const primaryCtaLabel = !bookingId || isExpired ? "요금 다시 확인하기" : "지금 예약하기";
  const shouldShowBackToRooms = propertyId > 0 && status.includes("재고 마감");
  const backToRoomsLink = useMemo(() => {
    if (propertyId <= 0) return null;
    const query = new URLSearchParams({
      check_in: checkIn,
      check_out: checkOut,
      adults: String(adults),
      children: String(children),
      rooms: String(rooms),
      place_label: propertyName,
    });
    return `/properties/${propertyId}?${query.toString()}#rooms`;
  }, [adults, checkIn, checkOut, children, propertyId, propertyName, rooms]);

  return (
    <section className="page checkout-page checkout-booking-page">
      <div className="checkout-clock-banner">
        <span>숙소 요청으로 조건 유지 중</span>
        <strong>{countdownText}</strong>
      </div>

      <ul className="checkout-steps" aria-label="예약 진행 단계">
        <li className="checkout-step completed">1. 고객 정보</li>
        <li className="checkout-step active">2. 결제 정보</li>
        <li className="checkout-step">3. 예약 완료</li>
      </ul>

      <div className="checkout-booking-layout">
        <div className="checkout-booking-main">
          <article className="checkout-card checkout-booking-section">
            <h3>결제 시기 선택</h3>
            <div className="checkout-radio-list">
              <label className="checkout-radio-item">
                <input
                  type="radio"
                  name="payment_timing"
                  checked={paymentTiming === "pay_later"}
                  onChange={() => setPaymentTiming("pay_later")}
                />
                <div>
                  <strong>체크인 직전 결제</strong>
                  <p>오늘 결제 금액이 없고, 표시된 무료 취소 기한 내 변경/취소가 가능합니다.</p>
                </div>
              </label>
              <label className="checkout-radio-item">
                <input
                  type="radio"
                  name="payment_timing"
                  checked={paymentTiming === "pay_now"}
                  onChange={() => setPaymentTiming("pay_now")}
                />
                <div>
                  <strong>지금 결제</strong>
                  <p>카드 또는 간편결제로 즉시 결제를 진행합니다.</p>
                </div>
              </label>
              {paymentTiming === "pay_now" && (
                <div className="checkout-pay-badges">
                  <span>VISA</span>
                  <span>Mastercard</span>
                  <span>AMEX</span>
                  <span>JCB</span>
                  <span>PayPal</span>
                </div>
              )}
            </div>
          </article>

          <article className="checkout-card checkout-booking-section">
            <h3>대표 투숙객 정보</h3>
            <div className="checkout-form-grid">
              <label>
                영문 이름(First Name) *
                <input value={leadFirstName} onChange={(e) => setLeadFirstName(e.target.value)} />
              </label>
              <label>
                영문 성(Last Name) *
                <input value={leadLastName} onChange={(e) => setLeadLastName(e.target.value)} />
              </label>
              <label className="field-full">
                이메일 주소 *
                <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" />
              </label>
              <label>
                거주 국가/지역 *
                <input value={country} onChange={(e) => setCountry(e.target.value)} />
              </label>
              <label>
                국가/지역 번호
                <input value={phoneCountryCode} onChange={(e) => setPhoneCountryCode(e.target.value)} />
              </label>
              <label className="field-full">
                휴대전화번호 *
                <input value={phoneNumber} onChange={(e) => setPhoneNumber(e.target.value)} />
              </label>
            </div>
          </article>

          <article className="checkout-card checkout-booking-section">
            <h3>특별 요청하기</h3>
            <div className="checkout-request-grid">
              <label><input type="checkbox" checked={specialHighFloor} onChange={(e) => setSpecialHighFloor(e.target.checked)} /> 고층 객실</label>
              <label><input type="checkbox" checked={specialQuietRoom} onChange={(e) => setSpecialQuietRoom(e.target.checked)} /> 조용한 객실</label>
              <label><input type="checkbox" checked={specialParking} onChange={(e) => setSpecialParking(e.target.checked)} /> 주차장 이용</label>
              <label><input type="checkbox" checked={specialLateArrival} onChange={(e) => setSpecialLateArrival(e.target.checked)} /> 늦은 도착 예정</label>
            </div>
            <label>
              추가 요청 사항
              <textarea
                value={specialNote}
                onChange={(e) => setSpecialNote(e.target.value)}
                rows={3}
                placeholder="영문 또는 한국어로 입력해 주세요."
              />
            </label>
            <label>
              숙소 도착 예정 시간
              <select value={arrivalTime} onChange={(e) => setArrivalTime(e.target.value)}>
                <option value="미정">미정</option>
                <option value="18:00 이전">18:00 이전</option>
                <option value="18:00-22:00">18:00-22:00</option>
                <option value="22:00 이후">22:00 이후</option>
              </select>
            </label>
          </article>

          <article className="checkout-card checkout-booking-section">
            <h3>약관 동의</h3>
            <div className="checkout-agreement-box">
              <label><input type="checkbox" checked={agreeAll} onChange={(e) => toggleAgreeAll(e.target.checked)} /> 다음의 모든 항목에 동의합니다.</label>
              <label><input type="checkbox" checked={agreeTerms} onChange={(e) => setAgreeTerms(e.target.checked)} /> 이용약관 동의 (필수)</label>
              <label><input type="checkbox" checked={agreePrivacy} onChange={(e) => setAgreePrivacy(e.target.checked)} /> 개인정보 처리방침 동의 (필수)</label>
              <label><input type="checkbox" checked={agreeThirdParty} onChange={(e) => setAgreeThirdParty(e.target.checked)} /> 제3자 정보 제공 동의 (필수)</label>
            </div>
          </article>

          <article className="checkout-card checkout-booking-section">
            <h3>무료 객실 혜택</h3>
            <ul className="checkout-benefit-list">
              <li>전액 환불 가능한 안심 옵션</li>
              <li>후지불 옵션 선택 시 오늘 결제 금액 없음</li>
              <li>무료 Wi-Fi</li>
              <li>숙소 내 주차 무료</li>
            </ul>
          </article>

          {queueTicket && (
            <div className="queue-box checkout-queue">
              <p>대기 번호: {queueTicket}</p>
              <p>현재 순번: {queuePosition ?? "-"}</p>
              <p>예상 대기 시간: {queueWaitSeconds ?? "-"}초</p>
            </div>
          )}
          {error && <p className="notice error">{error}</p>}
          {shouldShowBackToRooms && backToRoomsLink && (
            <Link className="inline-ghost checkout-back-to-rooms" to={backToRoomsLink}>
              다른 객실 보기
            </Link>
          )}
          {isExpired && <p className="notice warning">진행 시간이 만료되었습니다. 요금을 다시 확인해 주세요.</p>}

          <button
            className="checkout-primary-cta"
            onClick={() => {
              void handlePrimaryAction();
            }}
            disabled={!canSubmit}
          >
            {primaryCtaLabel}
          </button>
          <p className="checkout-note">
            결제 직전 재고와 요금을 다시 확인합니다. 다른 고객이 먼저 결제를 완료하면 해당 요금은 마감될 수 있습니다.
          </p>
        </div>

        <aside className="checkout-booking-side">
          <article className="checkout-card checkout-side-sticky">
            <h3>예약 요약</h3>
            <div className={`checkout-status ${statusDescriptor.tone}`}>
              <strong>{statusDescriptor.title}</strong>
              <p>{statusDescriptor.description}</p>
            </div>
            {expiresAt && <p className="checkout-expire">조건 표시 만료 시각: {toDateTimeLabel(expiresAt)}</p>}

            <dl className="checkout-summary">
              <div>
                <dt>체크인</dt>
                <dd>{toIsoDateLabel(holdBody.check_in)}</dd>
              </div>
              <div>
                <dt>체크아웃</dt>
                <dd>{toIsoDateLabel(holdBody.check_out)}</dd>
              </div>
              <div>
                <dt>투숙 정보</dt>
                <dd>{nights}박 · 객실 {holdBody.rooms}개 · 성인 {holdBody.guests.adults} · 아동 {holdBody.guests.children}</dd>
              </div>
            </dl>

            <div className="checkout-side-property">
              <strong>{propertyName}</strong>
              <p>{roomName}</p>
              <p>평점 {reviewScore.toFixed(1)} · 이용후기 {reviewCount.toLocaleString()}건</p>
            </div>

            <div className="checkout-coupon-box">
              <strong>할인 쿠폰 적용됨</strong>
              <p>WELCOME19 코드 적용 · -{couponDiscount.toLocaleString()} KRW</p>
            </div>

            <div className="checkout-price-table">
              <p><span>최초 요금</span><strong>{listPrice.toLocaleString()} KRW</strong></p>
              <p><span>당사 요금</span><strong>{holdBody.price.amount_total.toLocaleString()} KRW</strong></p>
              <p><span>할인 쿠폰</span><strong>-{couponDiscount.toLocaleString()} KRW</strong></p>
              <p><span>세금 및 제반요금</span><strong>{taxes.toLocaleString()} KRW</strong></p>
              <p className="total"><span>합계</span><strong>{grandTotal.toLocaleString()} KRW</strong></p>
            </div>

            <div className="checkout-cancel-policy">
              <strong>예약 취소 시 부과 요금</strong>
              <p>유연한 예약: 체크인 하루 전까지 무료 취소가 가능합니다.</p>
              <div className="checkout-cancel-timeline">
                <span className="active">오늘</span>
                <span>무료 취소 마감</span>
                <span>체크인</span>
              </div>
            </div>
          </article>
        </aside>
      </div>
    </section>
  );
}
