export type CheckoutFlow = "booking" | "ticket" | "package";
export type CheckoutStage = "hold" | "confirm" | "queue";

export type CheckoutApiError = {
  code?: string;
  message?: string;
};

type FriendlyCheckoutError = {
  status: string;
  message: string;
};

const SOLD_OUT_CODES = new Set([
  "BOOKING_OVERBOOKED",
  "TICKET_SOLD_OUT",
  "BOOKING_STATE_CONFLICT",
  "ORDER_STATE_CONFLICT",
  "INVENTORY_INVARIANT_VIOLATION",
]);

const EXPIRED_CODES = new Set([
  "BOOKING_EXPIRED",
  "ORDER_EXPIRED",
]);

const PAYMENT_CODES = new Set([
  "PAYMENT_AUTH_FAILED",
]);

function flowItemLabel(flow: CheckoutFlow): string {
  if (flow === "booking") return "객실/요금";
  if (flow === "ticket") return "좌석/요금";
  return "구성 상품";
}

export function toFriendlyCheckoutError(
  flow: CheckoutFlow,
  stage: CheckoutStage,
  error: CheckoutApiError,
): FriendlyCheckoutError {
  const code = (error.code ?? "").trim();

  if (stage === "queue" && code === "QUEUE_TOKEN_INVALID") {
    return {
      status: "대기열 만료",
      message: "접속 대기 시간이 만료되었습니다. 다시 시도해 주세요.",
    };
  }

  if (stage === "confirm" && SOLD_OUT_CODES.has(code)) {
    return {
      status: "재고 마감",
      message: `결제 직전 재고 재검증 중 다른 고객이 먼저 결제를 완료했습니다. 선택하신 ${flowItemLabel(flow)}은(는) 현재 마감되어 다른 옵션을 선택해 주세요.`,
    };
  }

  if (stage === "confirm" && EXPIRED_CODES.has(code)) {
    return {
      status: "보유 시간 만료",
      message: "결제 전에 보유 시간이 만료되었습니다. 다시 조회 후 예약을 진행해 주세요.",
    };
  }

  if (stage === "confirm" && PAYMENT_CODES.has(code)) {
    return {
      status: "결제 승인 실패",
      message: "결제 승인이 실패했습니다. 결제 수단을 확인한 뒤 다시 시도해 주세요.",
    };
  }

  const fallback = error.message ?? `${stage} 실패`;
  return {
    status: stage === "confirm" ? "CONFIRM 실패" : stage === "hold" ? "HOLD 실패" : "대기열 실패",
    message: code ? `${fallback} (${code})` : fallback,
  };
}
