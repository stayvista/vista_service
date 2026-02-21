import { FormEvent, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { GuestsPickerPopover } from "./GuestsPickerPopover";
import { PriceCalendarPopover } from "./PriceCalendarPopover";
import { getStaySearchInput, guestSummary, inferCityFromPlaceId } from "./searchState";
import { PlaceSuggestion, StaySearchInput } from "./searchTypes";
import { UnifiedAutocomplete } from "./UnifiedAutocomplete";

type HeroTab = "stay" | "flight_hotel" | "package" | "ticket";

type Props = {
  initial: StaySearchInput;
  onSearch: (next: StaySearchInput) => void;
  mode?: "hero" | "compact";
};

const HERO_TABS: Array<{ id: HeroTab; label: string }> = [
  { id: "stay", label: "숙소" },
  { id: "flight_hotel", label: "항공 + 숙소" },
  { id: "package", label: "패키지" },
  { id: "ticket", label: "티켓" },
];

export function HeroSearchBox({ initial, onSearch, mode = "hero" }: Props) {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<HeroTab>("stay");
  const [placeLabel, setPlaceLabel] = useState(initial.placeLabel);
  const [placeId, setPlaceId] = useState<string | undefined>(initial.placeId);
  const [city, setCity] = useState<string | undefined>(initial.city);
  const [district, setDistrict] = useState<string | undefined>(initial.district);
  const [checkIn, setCheckIn] = useState(initial.checkIn);
  const [checkOut, setCheckOut] = useState(initial.checkOut);
  const [guests, setGuests] = useState(initial.guests);

  const [flightFrom, setFlightFrom] = useState("서울");
  const [flightTo, setFlightTo] = useState("도쿄");
  const [flightDate, setFlightDate] = useState(initial.checkIn);
  const [ticketKeyword, setTicketKeyword] = useState("");

  useEffect(() => {
    setPlaceLabel(initial.placeLabel);
    setPlaceId(initial.placeId);
    setCity(initial.city);
    setDistrict(initial.district);
    setCheckIn(initial.checkIn);
    setCheckOut(initial.checkOut);
    setGuests(initial.guests);
  }, [initial]);

  const sectionClassName = useMemo(
    () => (mode === "compact" ? "hero-searchbox compact" : "hero-searchbox"),
    [mode],
  );

  function handleStaySubmit(event?: FormEvent) {
    event?.preventDefault();

    const normalizedLabel = placeLabel.normalize("NFC").trim();
    const resolvedPlaceId = placeId && placeId.trim() ? placeId : undefined;
    const resolvedCity = city?.trim() || inferCityFromPlaceId(resolvedPlaceId) || normalizedLabel || "Seoul";

    onSearch({
      placeId: resolvedPlaceId,
      placeLabel: normalizedLabel || resolvedCity,
      city: resolvedCity,
      district,
      checkIn,
      checkOut,
      guests,
      currency: initial.currency,
    });
  }

  function handlePlaceSelect(row: PlaceSuggestion) {
    const selectedPlaceId = row.placeId ?? row.id;
    const selectedCity = row.city ?? inferCityFromPlaceId(selectedPlaceId) ?? city;
    setPlaceId(selectedPlaceId);
    setPlaceLabel(row.display);
    setCity(selectedCity);
    setDistrict(row.district);
  }

  function handleFreeTextSubmit(nextValue: string) {
    const normalized = nextValue.normalize("NFC").trim();
    setPlaceId(undefined);
    setPlaceLabel(normalized);
    setCity(normalized || undefined);
    setDistrict(undefined);
  }

  function handleOtherTabSubmit(event: FormEvent) {
    event.preventDefault();
    if (activeTab === "flight_hotel") {
      const query = new URLSearchParams({
        from: flightFrom,
        to: flightTo,
        date: flightDate,
        adults: String(guests.adults),
      });
      navigate(`/packages?${query.toString()}`);
      return;
    }
    if (activeTab === "package") {
      const query = new URLSearchParams({ city: city ?? (placeLabel || "Seoul") });
      navigate(`/packages?${query.toString()}`);
      return;
    }

    const keyword = ticketKeyword.trim() || city || placeLabel || "서울";
    navigate(`/tickets?q=${encodeURIComponent(keyword)}`);
  }

  const showTabs = mode === "hero";

  return (
    <section className={sectionClassName}>
      {showTabs && (
        <div className="service-tabs" role="tablist" aria-label="서비스 선택">
          {HERO_TABS.map((tab) => (
            <button
              key={tab.id}
              type="button"
              className={activeTab === tab.id ? "service-tab active" : "service-tab"}
              aria-selected={activeTab === tab.id}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </div>
      )}

      {activeTab === "stay" || mode === "compact" ? (
        <form className={mode === "compact" ? "stay-search-form compact" : "stay-search-form"} onSubmit={handleStaySubmit}>
          <UnifiedAutocomplete
            className="search-place-input"
            value={placeLabel}
            placeId={placeId}
            cityHint={city}
            placeholder="도시, 지역, 숙소, 명소를 입력하세요"
            onChange={(next) => {
              setPlaceLabel(next);
              if (!next.trim()) {
                setPlaceId(undefined);
              }
            }}
            onSelect={handlePlaceSelect}
            onSubmitFreeText={handleFreeTextSubmit}
          />
          <PriceCalendarPopover
            placeId={placeId}
            cityHint={city}
            currency={initial.currency}
            guests={guests}
            checkIn={checkIn}
            checkOut={checkOut}
            onApply={({ checkIn: nextCheckIn, checkOut: nextCheckOut }) => {
              setCheckIn(nextCheckIn);
              setCheckOut(nextCheckOut);
            }}
          />
          <GuestsPickerPopover
            value={guests}
            onApply={(next) => {
              setGuests(next);
            }}
          />
          {mode === "compact" && <p className="compact-summary">{guestSummary(guests)}</p>}
          <button type="submit" className="search-cta">검색</button>
        </form>
      ) : (
        <form className="alt-search-form" onSubmit={handleOtherTabSubmit}>
          {activeTab === "flight_hotel" && (
            <>
              <input value={flightFrom} onChange={(event) => setFlightFrom(event.target.value)} placeholder="출발지" />
              <input value={flightTo} onChange={(event) => setFlightTo(event.target.value)} placeholder="도착지" />
              <input type="date" value={flightDate} onChange={(event) => setFlightDate(event.target.value)} />
              <GuestsPickerPopover value={guests} onApply={setGuests} />
            </>
          )}
          {activeTab === "package" && (
            <>
              <input
                value={placeLabel}
                onChange={(event) => setPlaceLabel(event.target.value)}
                placeholder="여행 도시 또는 지역"
              />
              <input type="date" value={checkIn} onChange={(event) => setCheckIn(event.target.value)} />
              <input type="date" value={checkOut} onChange={(event) => setCheckOut(event.target.value)} />
              <GuestsPickerPopover value={guests} onApply={setGuests} />
            </>
          )}
          {activeTab === "ticket" && (
            <>
              <input
                value={ticketKeyword}
                onChange={(event) => setTicketKeyword(event.target.value)}
                placeholder="도시/공연/전시/액티비티"
              />
              <input type="date" value={checkIn} onChange={(event) => setCheckIn(event.target.value)} />
            </>
          )}
          <button type="submit" className="search-cta">검색</button>
        </form>
      )}
    </section>
  );
}

export function getDefaultStaySearch(currency: string): StaySearchInput {
  const params = new URLSearchParams();
  params.set("currency", currency);
  return getStaySearchInput(params, currency);
}
