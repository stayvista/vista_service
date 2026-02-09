import { FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { useNavigate } from "react-router-dom";

type CityOption = {
  value: string;
  label: string;
  blurb: string;
};

type DestinationCard = {
  id: string;
  title: string;
  subtitle: string;
  city: string;
  image: string;
};

const cityOptions: CityOption[] = [
  { value: "Seoul", label: "서울", blurb: "도심 럭셔리, 미식, 컨시어지 서비스" },
  { value: "Busan", label: "부산", blurb: "오션뷰 호텔, 해변 액티비티, 야경" },
  { value: "Jeju", label: "제주", blurb: "리조트, 프라이빗 풀빌라, 자연 체험" },
  { value: "Daegu", label: "대구", blurb: "로컬 감성 스테이, 전통 문화, 휴식" },
];

const quickFilters = ["오션뷰", "프라이빗 풀", "조식 포함", "무료 취소", "24시간 체크인"];

const featuredDestinations: DestinationCard[] = [
  {
    id: "seoul",
    title: "서울 시그니처 스테이",
    subtitle: "프리미엄 비즈니스 & 시티뷰",
    city: "Seoul",
    image: "https://picsum.photos/seed/stayvista-seoul/880/540",
  },
  {
    id: "busan",
    title: "부산 오션 프런트",
    subtitle: "해변 도보권 리조트 큐레이션",
    city: "Busan",
    image: "https://picsum.photos/seed/stayvista-busan/880/540",
  },
  {
    id: "jeju",
    title: "제주 프라이빗 리조트",
    subtitle: "자연 속 웰니스·가족형 숙소",
    city: "Jeju",
    image: "https://picsum.photos/seed/stayvista-jeju/880/540",
  },
  {
    id: "daegu",
    title: "대구 컬처 라운지",
    subtitle: "핵심 상권 중심 프리미엄 객실",
    city: "Daegu",
    image: "https://picsum.photos/seed/stayvista-daegu/880/540",
  },
];

function dateOffset(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function HomePage() {
  const navigate = useNavigate();
  const [city, setCity] = useState("Seoul");
  const [checkIn, setCheckIn] = useState(dateOffset(7));
  const [checkOut, setCheckOut] = useState(dateOffset(9));
  const [adults, setAdults] = useState(2);
  const [children, setChildren] = useState(0);
  const selectedCity = cityOptions.find((option) => option.value === city);

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
          <h1>끝내주는 여행을 시작하세요</h1>
          <p className="hero-summary">
            숙소, 티켓, 패키지 재고를 하나의 화면에서 실시간으로 확인하고
            안전하게 예약까지 하실수 있습니다!
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
          <h2>지금 가장 빠르게 예약되는 목적지</h2>
          <Link to="/search" className="section-link">전체 숙소 보기</Link>
        </div>
        <ul className="destination-grid">
          {featuredDestinations.map((destination) => (
            <li key={destination.id} className="destination-card">
              <Link to={`/search?city=${encodeURIComponent(destination.city)}`}>
                <img src={destination.image} alt={destination.title} loading="lazy" />
                <div className="destination-meta">
                  <p>{destination.subtitle}</p>
                  <h3>{destination.title}</h3>
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
