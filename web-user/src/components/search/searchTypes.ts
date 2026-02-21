export type PlaceSuggestion = {
  id: string;
  type: string;
  placeId?: string;
  city?: string;
  district?: string;
  display: string;
  subtitle?: string;
  highlight?: string;
  bucket?: string;
};

export type GuestState = {
  rooms: number;
  adults: number;
  children: number;
  childrenAges: number[];
};

export type StaySearchInput = {
  placeId?: string;
  placeLabel: string;
  city?: string;
  district?: string;
  checkIn: string;
  checkOut: string;
  guests: GuestState;
  currency: string;
};

export type PriceCalendarDay = {
  date: string;
  min_price: number | null;
  currency: string;
  available: boolean;
};

export type FacetItem = {
  key: string;
  label: string;
  count: number;
  group?: string;
};
