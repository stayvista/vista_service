import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useNavigate } from "react-router-dom";
import { apiGet } from "../api/client";

type CityOption = {
  value: string;
  label: string;
  blurb: string;
};

type FeaturedHotel = {
  property_id: number;
  name: string;
  city?: string;
  price_min?: number;
  rating?: number;
  thumbnail_url?: string | null;
};

const cityOptions: CityOption[] = [
  { value: "Seoul", label: "서울", blurb: "도심 럭셔리, 미식, 컨시어지 서비스" },
  { value: "Busan", label: "부산", blurb: "오션뷰 호텔, 해변 액티비티, 야경" },
  { value: "Jeju", label: "제주", blurb: "리조트, 프라이빗 풀빌라, 자연 체험" },
  { value: "Daegu", label: "대구", blurb: "로컬 감성 스테이, 전통 문화, 휴식" },
];

const quickFilters = ["오션뷰", "프라이빗 풀", "조식 포함", "무료 취소", "24시간 체크인"];

function dateOffset(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function pickFeaturedHotels(items: FeaturedHotel[]): FeaturedHotel[] {
  const selected: FeaturedHotel[] = [];
  const seenCity = new Set<string>();

  for (const item of items) {
    const cityKey = item.city ?? "";
    if (cityKey && !seenCity.has(cityKey)) {
      selected.push(item);
      seenCity.add(cityKey);
    }
    if (selected.length >= 4) break;
  }

  for (const item of items) {
    if (selected.length >= 4) break;
    if (!selected.some((selectedItem) => selectedItem.property_id === item.property_id)) {
      selected.push(item);
    }
  }

  return selected.slice(0, 4);
}

export function HomePage() {
  const navigate = useNavigate();
  const [city, setCity] = useState("Seoul");
  const [checkIn, setCheckIn] = useState(dateOffset(7));
  const [checkOut, setCheckOut] = useState(dateOffset(9));
  const [adults, setAdults] = useState(2);
  const [children, setChildren] = useState(0);
  const [featuredHotels, setFeaturedHotels] = useState<FeaturedHotel[]>([]);
  const [featuredLoading, setFeaturedLoading] = useState(false);
  const [featuredError, setFeaturedError] = useState<string | null>(null);
  const selectedCity = cityOptions.find((option) => option.value === city);

  useEffect(() => {
    setFeaturedLoading(true);
    setFeaturedError(null);
    apiGet<{ items: FeaturedHotel[] }>("/v1/search/properties?sort=rating_desc&limit=20")
      .then((response) => {
        setFeaturedHotels(pickFeaturedHotels(response.data.items ?? []));
      })
      .catch((e: unknown) => {
        const err = e as { code?: string; message?: string };
        setFeaturedHotels([]);
        setFeaturedError(`${err.code ?? "ERROR"}: ${err.message ?? "추천 호텔 조회 실패"}`);
      })
      .finally(() => {
        setFeaturedLoading(false);
      });
  }, []);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    const query = new URLSearchParams({
      city,
      check_in: checkIn,
      check_out: checkOut,
      adults: String(adults),
      children: String(children),
    });
    navigate(`/search?${query.toString()}`);
  };

  return (
    <>
      <section className="hero">
        <div className="hero-content">
          <p className="hero-eyebrow">VERIFIED INVENTORY · REAL-TIME BOOKING</p>
          <h1>잊지못할 여행을 선물하세요</h1>
          <p className="hero-summary">
            숙소, 티켓, 패키지 재고를 하나의 화면에서 실시간으로 확인하고
            예약 하실수 있습니다.
          </p>
          <div className="service-tabs" role="tablist" aria-label="서비스 선택">
            <button type="button" className="service-tab active" aria-selected="true">숙소</button>
            <button type="button" className="service-tab" aria-selected="false">항공 + 숙소</button>
            <button type="button" className="service-tab" aria-selected="false">패키지</button>
            <button type="button" className="service-tab" aria-selected="false">티켓</button>
          </div>
          <form className="search-form premium-search" onSubmit={onSubmit}>
            <label className="field-group field-wide">
              목적지
              <select value={city} onChange={(e) => setCity(e.target.value)} aria-label="도시 선택">
                {cityOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              <span className="field-hint">{selectedCity?.blurb}</span>
            </label>
            <label className="field-group">
              체크인
              <input type="date" value={checkIn} onChange={(e) => setCheckIn(e.target.value)} />
            </label>
            <label className="field-group">
              체크아웃
              <input type="date" value={checkOut} onChange={(e) => setCheckOut(e.target.value)} />
            </label>
            <label className="field-group">
              성인
              <input type="number" min={1} value={adults} onChange={(e) => setAdults(Number(e.target.value))} />
            </label>
            <label className="field-group">
              어린이
              <input type="number" min={0} value={children} onChange={(e) => setChildren(Number(e.target.value))} />
            </label>
            <button type="submit" className="search-cta">검색하기</button>
          </form>
          <div className="quick-filters">
            {quickFilters.map((filter) => (
              <button key={filter} type="button" className="quick-chip">{filter}</button>
            ))}
          </div>
          <ul className="hero-metrics">
            <li>
              <strong>1.2M+</strong>
              <span>누적 예약 건수</span>
            </li>
            <li>
              <strong>4.8 / 5</strong>
              <span>실제 투숙 후기 평점</span>
            </li>
            <li>
              <strong>24/7</strong>
              <span>운영 지원 및 고객 응대</span>
            </li>
          </ul>
        </div>
      </section>

      <section className="home-section">
        <div className="section-head">
          <h2>지금 가장 빠르게 예약되는 호텔</h2>
          <Link to="/search" className="section-link">전체 숙소 보기</Link>
        </div>
        {featuredLoading && <p className="notice info">실제 숙소 데이터를 불러오는 중입니다...</p>}
        {featuredError && <p className="notice warning">{featuredError}</p>}
        {!featuredLoading && !featuredError && featuredHotels.length === 0 && (
          <p className="notice warning">표시할 숙소 데이터가 없습니다. 시드를 먼저 확인해 주세요.</p>
        )}
        <ul className="destination-grid">
          {featuredHotels.map((hotel) => (
            <li key={hotel.property_id} className="destination-card">
              <Link to={`/properties/${hotel.property_id}`}>
                <img
                  src={hotel.thumbnail_url || `https://picsum.photos/seed/property-${hotel.property_id}/880/540`}
                  alt={hotel.name}
                  loading="lazy"
                />
                <div className="destination-meta">
                  <p className="destination-subtitle">
                    {hotel.city ?? "도시 미지정"} · 평점 {(hotel.rating ?? 0).toFixed(1)} · 최저가 {(hotel.price_min ?? 0).toLocaleString()} KRW
                  </p>
                  <h3>{hotel.name}</h3>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      </section>

      <section className="home-section">
        <div className="section-head">
          <h2>StayVista 운영 원칙</h2>
        </div>
        <ul className="pillar-grid">
          <li className="pillar-card">
            <h3>실시간 재고 동기화</h3>
            <p>숙소 재고와 가격이 즉시 반영되어 예약 실패 확률을 최소화합니다.</p>
          </li>
          <li className="pillar-card">
            <h3>검증된 파트너만 노출</h3>
            <p>품질 기준을 통과한 운영 파트너의 객실과 상품만 제공합니다.</p>
          </li>
          <li className="pillar-card">
            <h3>예약 후 운영지원</h3>
            <p>변경·문의·환불 요청을 빠르게 처리하는 전담 지원 채널을 제공합니다.</p>
          </li>
        </ul>
      </section>
    </>
  );
}
