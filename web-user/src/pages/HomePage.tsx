import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiGet } from "../api/client";
import { useLocale } from "../components/locale/LocaleContext";
import { HeroSearchBox } from "../components/search/HeroSearchBox";
import { getStaySearchInput, setStaySearchParams } from "../components/search/searchState";
import { StaySearchInput } from "../components/search/searchTypes";

type FeaturedHotel = {
  property_id: number;
  name: string;
  city?: string;
  price_min?: number;
  rating?: number;
  currency?: string;
  thumbnail_url?: string | null;
};

type ApiError = { code?: string; message?: string };

const quickFilters = ["오션뷰", "프라이빗 풀", "조식 포함", "무료 취소", "24시간 체크인"];

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
    if (!selected.some((current) => current.property_id === item.property_id)) {
      selected.push(item);
    }
  }

  return selected.slice(0, 4);
}

export function HomePage() {
  const navigate = useNavigate();
  const { locale } = useLocale();
  const [featuredHotels, setFeaturedHotels] = useState<FeaturedHotel[]>([]);
  const [featuredLoading, setFeaturedLoading] = useState(false);
  const [featuredError, setFeaturedError] = useState<string | null>(null);

  const initialSearch = useMemo<StaySearchInput>(() => {
    const seed = new URLSearchParams({ currency: locale.currency });
    const base = getStaySearchInput(seed, locale.currency);
    return {
      ...base,
      placeId: "city:Seoul",
      placeLabel: "서울",
      city: "Seoul",
      currency: locale.currency,
    };
  }, [locale.currency]);

  useEffect(() => {
    setFeaturedLoading(true);
    setFeaturedError(null);

    const query = new URLSearchParams({
      sort: "rating_desc",
      size: "20",
      currency: locale.currency,
      city: "Seoul",
    });

    apiGet<{ items: FeaturedHotel[] }>(`/v1/search/properties?${query.toString()}`)
      .then((response) => {
        setFeaturedHotels(pickFeaturedHotels(response.data.items ?? []));
      })
      .catch((e: unknown) => {
        const err = e as ApiError;
        setFeaturedHotels([]);
        setFeaturedError(`${err.code ?? "ERROR"}: ${err.message ?? "추천 호텔 조회 실패"}`);
      })
      .finally(() => {
        setFeaturedLoading(false);
      });
  }, [locale.currency]);

  function onSearch(next: StaySearchInput) {
    const params = setStaySearchParams(new URLSearchParams(), next);
    navigate(`/search?${params.toString()}`);
  }

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

          <HeroSearchBox initial={initialSearch} onSearch={onSearch} mode="hero" />

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
                    {hotel.city ?? "도시 미지정"} · 평점 {(hotel.rating ?? 0).toFixed(1)} · 최저가 {(hotel.price_min ?? 0).toLocaleString()} {hotel.currency ?? locale.currency}
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
