import { GuestState, StaySearchInput } from "./searchTypes";

export function dateOffset(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return toDateString(date);
}

export function toDateString(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function parseGuestState(params: URLSearchParams): GuestState {
  const rooms = Number(params.get("rooms") ?? "1");
  const adults = Number(params.get("adults") ?? "2");
  const children = Number(params.get("children") ?? "0");
  const childrenAges = (params.get("children_ages") ?? "")
    .split(",")
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isFinite(value))
    .map((value) => clamp(value, 0, 17));

  const normalizedChildren = clamp(children, 0, 8);
  const normalizedAges = childrenAges.slice(0, normalizedChildren);
  while (normalizedAges.length < normalizedChildren) {
    normalizedAges.push(7);
  }

  return {
    rooms: clamp(rooms, 1, 8),
    adults: clamp(adults, 1, 16),
    children: normalizedChildren,
    childrenAges: normalizedAges,
  };
}

export function guestSummary(guests: GuestState): string {
  return `객실 ${guests.rooms} · 성인 ${guests.adults} · 아동 ${guests.children}`;
}

export function serializeGuests(guests: GuestState): Record<string, string> {
  const normalizedChildrenAges = guests.childrenAges.slice(0, guests.children);
  return {
    rooms: String(clamp(guests.rooms, 1, 8)),
    adults: String(clamp(guests.adults, 1, 16)),
    children: String(clamp(guests.children, 0, 8)),
    children_ages: normalizedChildrenAges.join(","),
  };
}

export function setStaySearchParams(params: URLSearchParams, value: StaySearchInput): URLSearchParams {
  const next = new URLSearchParams(params);

  next.delete("cursor");
  next.set("check_in", value.checkIn);
  next.set("check_out", value.checkOut);
  next.set("currency", value.currency);

  const guestEntries = serializeGuests(value.guests);
  next.set("rooms", guestEntries.rooms);
  next.set("adults", guestEntries.adults);
  next.set("children", guestEntries.children);
  if (guestEntries.children_ages) {
    next.set("children_ages", guestEntries.children_ages);
  } else {
    next.delete("children_ages");
  }

  if (value.placeId) {
    next.set("place_id", value.placeId);
    next.set("place_label", value.placeLabel);
    if (value.city) {
      next.set("city", value.city);
    } else {
      next.delete("city");
    }
  } else {
    next.delete("place_id");
    next.delete("place_label");
    if (value.city) {
      next.set("city", value.city);
    } else {
      next.delete("city");
    }
  }

  if (value.district) {
    next.set("districts", value.district);
  }

  return next;
}

export function getStaySearchInput(params: URLSearchParams, fallbackCurrency: string): StaySearchInput {
  const placeId = params.get("place_id") ?? undefined;
  const city = params.get("city") ?? inferCityFromPlaceId(placeId) ?? "Seoul";

  return {
    placeId,
    placeLabel: params.get("place_label") ?? city,
    city,
    district: params.get("districts")?.split(",")[0] ?? undefined,
    checkIn: params.get("check_in") ?? dateOffset(7),
    checkOut: params.get("check_out") ?? dateOffset(9),
    guests: parseGuestState(params),
    currency: params.get("currency") ?? fallbackCurrency,
  };
}

export function inferCityFromPlaceId(placeId?: string | null): string | undefined {
  if (!placeId) {
    return undefined;
  }
  if (placeId.startsWith("city:")) {
    return placeId.slice("city:".length);
  }
  return undefined;
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) {
    return min;
  }
  return Math.max(min, Math.min(max, Math.trunc(value)));
}
