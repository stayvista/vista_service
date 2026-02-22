import { CSSProperties, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";
import { useLocale } from "../components/locale/LocaleContext";
import { HeroSearchBox } from "../components/search/HeroSearchBox";
import { getStaySearchInput, setStaySearchParams } from "../components/search/searchState";
import { StaySearchInput } from "../components/search/searchTypes";
import { getAuthUser } from "../auth/session";

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

type QuickFilter = {
  label: string;
  filter_key: string;
  filter_value: string;
};

type SearchMeta = {
  total: number;
};

type PromotionCampaign = {
  campaign_id: number;
  section: string;
  title: string;
  subtitle?: string;
  description?: string;
  city?: string;
  image_url?: string;
  badge_text?: string;
  discount_text?: string;
  coupon_value_type: string;
  coupon_value: number;
  currency: string;
  min_order_amount: number;
  issue_limit: number;
  issued_count: number;
  remaining_count: number;
  claimable: boolean;
  starts_at: string;
  ends_at: string;
};

type PromotionCampaignListData = {
  section: string;
  items: PromotionCampaign[];
};

type PromotionClaimData = {
  campaign_id: number;
  claim_id: number;
  coupon_code: string;
  already_claimed: boolean;
  remaining_count: number;
  expires_at: string;
  message: string;
};

type DestinationCard = {
  city: string;
  country?: string | null;
  label: string;
  image_url?: string | null;
  property_count: number;
  highlights?: string | null;
};

type HomeHeroMetric = {
  metric_value: string;
  metric_label: string;
};

type HomeHeroData = {
  eyebrow_text: string;
  title_text: string;
  summary_text: string;
  background_image_url?: string | null;
  metrics: HomeHeroMetric[];
};

type HomeDestinationSection = {
  section_code: string;
  items: DestinationCard[];
};

type HomePromotionSection = {
  section_code: string;
  title: string;
  subtitle?: string | null;
};

type HomeContentData = {
  hero?: HomeHeroData | null;
  quick_filters: QuickFilter[];
  destination_sections: HomeDestinationSection[];
  promotion_sections: HomePromotionSection[];
};

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
  const [selectedQuickFilters, setSelectedQuickFilters] = useState<string[]>([]);
  const [featuredHotels, setFeaturedHotels] = useState<FeaturedHotel[]>([]);
  const [homeContent, setHomeContent] = useState<HomeContentData | null>(null);
  const [promotionsBySection, setPromotionsBySection] = useState<Record<string, PromotionCampaign[]>>({});
  const [claimingCampaignIds, setClaimingCampaignIds] = useState<Record<number, boolean>>({});
  const [claimNotice, setClaimNotice] = useState<string | null>(null);
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

    Promise.all([
      apiGet<{ items: FeaturedHotel[]; meta?: SearchMeta }>(`/v1/search/properties?${query.toString()}`),
      apiGet<HomeContentData>("/v1/home/content"),
    ])
      .then(([featuredResponse, homeContentResponse]) => {
        setFeaturedHotels(pickFeaturedHotels(featuredResponse.data.items ?? []));
        setHomeContent(homeContentResponse.data);
      })
      .catch((e: unknown) => {
        const err = e as ApiError;
        setFeaturedHotels([]);
        setHomeContent(null);
        setFeaturedError(`${err.code ?? "ERROR"}: ${err.message ?? "추천 호텔 조회 실패"}`);
      })
      .finally(() => {
        setFeaturedLoading(false);
      });
  }, [locale.currency]);

  useEffect(() => {
    const promotionSections = homeContent?.promotion_sections ?? [];
    if (!promotionSections.length) {
      setPromotionsBySection({});
      return;
    }

    Promise.allSettled(
      promotionSections.map((section) =>
        apiGet<PromotionCampaignListData>(
          `/v1/promotions/campaigns?section=${section.section_code}&city=Seoul&limit=12`,
        ),
      ),
    ).then((results) => {
      const nextPromotions: Record<string, PromotionCampaign[]> = {};
      results.forEach((result, index) => {
        const section = promotionSections[index];
        if (result.status === "fulfilled") {
          nextPromotions[section.section_code] = result.value.data.items ?? [];
        } else {
          nextPromotions[section.section_code] = [];
        }
      });
      setPromotionsBySection(nextPromotions);
    });
  }, [homeContent?.promotion_sections]);

  function onSearch(next: StaySearchInput) {
    const params = setStaySearchParams(new URLSearchParams(), next);
    const grouped = new Map<string, Set<string>>();
    selectedQuickFilters.forEach((label) => {
      const filter = homeContent?.quick_filters.find((item) => item.label === label);
      if (!filter) {
        return;
      }
      const bucket = grouped.get(filter.filter_key) ?? new Set<string>();
      bucket.add(filter.filter_value);
      grouped.set(filter.filter_key, bucket);
    });
    grouped.forEach((values, key) => {
      if (values.size > 0) {
        params.set(key, Array.from(values).join(","));
      }
    });
    navigate(`/search?${params.toString()}`);
  }

  function toggleQuickFilter(label: string) {
    setSelectedQuickFilters((prev) =>
      prev.includes(label) ? prev.filter((item) => item !== label) : [...prev, label],
    );
  }

  function buildCitySearchHref(card: DestinationCard): string {
    const next = setStaySearchParams(
      new URLSearchParams(),
      {
        ...initialSearch,
        placeId: `city:${card.city}`,
        placeLabel: card.label,
        city: card.city,
        district: undefined,
        currency: locale.currency,
      },
    );
    return `/search?${next.toString()}`;
  }

  const hero = homeContent?.hero ?? null;
  const quickFilters = homeContent?.quick_filters ?? [];

  const domesticDestinations = useMemo(
    () => homeContent?.destination_sections.find((section) => section.section_code === "DOMESTIC")?.items ?? [],
    [homeContent?.destination_sections],
  );
  const globalDestinations = useMemo(
    () => homeContent?.destination_sections.find((section) => section.section_code === "GLOBAL")?.items ?? [],
    [homeContent?.destination_sections],
  );
  const heroStyle = useMemo<CSSProperties | undefined>(() => {
    if (!hero?.background_image_url) return undefined;
    return {
      backgroundImage: `linear-gradient(135deg, rgba(7, 17, 37, 0.72) 0%, rgba(8, 31, 67, 0.4) 60%, rgba(11, 52, 117, 0.2) 100%), url("${hero.background_image_url}")`,
    };
  }, [hero?.background_image_url]);

  function formatCouponValue(campaign: PromotionCampaign): string {
    if (campaign.coupon_value_type === "AMOUNT") {
      return `${currencySymbol(campaign.currency)}${campaign.coupon_value.toLocaleString()}`;
    }
    return `${campaign.coupon_value}%`;
  }

  function formatDateRange(campaign: PromotionCampaign): string {
    const starts = new Date(campaign.starts_at);
    const ends = new Date(campaign.ends_at);
    return `${starts.getMonth() + 1}/${starts.getDate()} ~ ${ends.getMonth() + 1}/${ends.getDate()}`;
  }

  async function claimCampaign(campaignId: number) {
    if (!getAuthUser()) {
      setClaimNotice("쿠폰 발급은 로그인 후 이용할 수 있습니다.");
      navigate("/login");
      return;
    }

    setClaimingCampaignIds((prev) => ({ ...prev, [campaignId]: true }));
    setClaimNotice(null);
    try {
      const response = await apiPost<PromotionClaimData>(`/v1/promotions/campaigns/${campaignId}/claim`, {});
      const claimed = response.data;
      setPromotionsBySection((prev) => {
        const next: Record<string, PromotionCampaign[]> = {};
        Object.entries(prev).forEach(([section, campaigns]) => {
          next[section] = campaigns.map((campaign) => {
            if (campaign.campaign_id !== campaignId) {
              return campaign;
            }
            const remaining = claimed.remaining_count;
            return {
              ...campaign,
              issued_count: Math.min(campaign.issue_limit, campaign.issue_limit - remaining),
              remaining_count: remaining,
              claimable: !claimed.already_claimed && remaining > 0,
            };
          });
        });
        return next;
      });
      setClaimNotice(`${claimed.message} 코드: ${claimed.coupon_code}`);
    } catch (e: unknown) {
      const err = e as ApiError;
      setClaimNotice(`${err.code ?? "ERROR"}: ${err.message ?? "쿠폰 발급에 실패했습니다."}`);
    } finally {
      setClaimingCampaignIds((prev) => ({ ...prev, [campaignId]: false }));
    }
  }

  return (
    <>
      <section className="hero" style={heroStyle}>
        <div className="hero-content">
          <p className="hero-eyebrow">{hero?.eyebrow_text ?? "STAYVISTA"}</p>
          <h1>{hero?.title_text ?? "잊지못할 여행을 선물하세요"}</h1>
          <p className="hero-summary">
            {hero?.summary_text ?? "숙소, 티켓, 패키지 재고를 하나의 화면에서 실시간으로 확인하고 예약 하실수 있습니다."}
          </p>

          <HeroSearchBox initial={initialSearch} onSearch={onSearch} mode="hero" />

          <div className="quick-filters">
            {quickFilters.map((filter) => (
              <button
                key={filter.label}
                type="button"
                className={selectedQuickFilters.includes(filter.label) ? "quick-chip active" : "quick-chip"}
                aria-pressed={selectedQuickFilters.includes(filter.label)}
                onClick={() => toggleQuickFilter(filter.label)}
              >
                {filter.label}
              </button>
            ))}
          </div>
          {hero?.metrics?.length ? (
            <ul className="hero-metrics">
              {hero.metrics.map((metric) => (
                <li key={`${metric.metric_value}-${metric.metric_label}`}>
                  <strong>{metric.metric_value}</strong>
                  <span>{metric.metric_label}</span>
                </li>
              ))}
            </ul>
          ) : null}
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
                {hotel.thumbnail_url ? (
                  <img
                    src={hotel.thumbnail_url}
                    alt={hotel.name}
                    loading="lazy"
                  />
                ) : (
                  <div className="destination-thumb-empty">이미지 준비중</div>
                )}
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
          <h2>대한민국 내 인기 여행지</h2>
        </div>
        {!domesticDestinations.length && <p className="notice info">도시 데이터 준비중입니다.</p>}
        <ul className="home-scroll-list city-scroll-list">
          {domesticDestinations.map((card) => (
            <li key={`domestic-${card.city}`} className="city-scroll-card">
              <Link to={buildCitySearchHref(card)}>
                {card.image_url ? (
                  <img src={card.image_url} alt={`${card.label} 여행지`} loading="lazy" />
                ) : (
                  <div className="city-thumb-empty">이미지 준비중</div>
                )}
                <div className="city-scroll-meta">
                  <h3>{card.label}</h3>
                  <p>숙소 {card.property_count.toLocaleString()}개</p>
                  <span>{card.highlights ?? "주요 정보 준비중"}</span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      </section>

      {(homeContent?.promotion_sections ?? []).map((section) => {
        const campaigns = promotionsBySection[section.section_code] ?? [];
        return (
          <section className="home-section" key={`promotion-${section.section_code}`}>
            <div className="section-head">
              <h2>{section.title}</h2>
              <p className="section-head-note">{section.subtitle}</p>
            </div>
            {campaigns.length === 0 ? (
              <p className="notice info">진행 중인 프로모션이 없습니다.</p>
            ) : (
              <ul className="home-scroll-list promo-scroll-list">
                {campaigns.map((campaign) => (
                  <li key={campaign.campaign_id} className="promo-scroll-card">
                    {campaign.image_url ? (
                      <img
                        src={campaign.image_url}
                        alt={campaign.title}
                        loading="lazy"
                      />
                    ) : (
                      <div className="promo-thumb-empty">이미지 준비중</div>
                    )}
                    <div className="promo-card-overlay">
                      <p className="promo-badge">
                        {campaign.badge_text || "PROMO"}
                      </p>
                      <h3>{campaign.title}</h3>
                      <p>{campaign.subtitle || campaign.description || "기간 한정 혜택"}</p>
                      <div className="promo-meta-row">
                        <span>{campaign.discount_text || `${formatCouponValue(campaign)} 쿠폰`}</span>
                        <span>{formatDateRange(campaign)}</span>
                      </div>
                      <div className="promo-meta-row">
                        <span>남은 수량 {campaign.remaining_count.toLocaleString()}개</span>
                        <button
                          type="button"
                          className={campaign.claimable ? "promo-claim-btn" : "promo-claim-btn disabled"}
                          disabled={!campaign.claimable || claimingCampaignIds[campaign.campaign_id]}
                          onClick={() => claimCampaign(campaign.campaign_id)}
                        >
                          {claimingCampaignIds[campaign.campaign_id]
                            ? "발급 중..."
                            : campaign.claimable
                              ? "쿠폰 받기"
                              : "소진/종료"}
                        </button>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        );
      })}

      {claimNotice && (
        <section className="home-section home-notice-section">
          <p className="notice info">{claimNotice}</p>
        </section>
      )}

      <section className="home-section">
        <div className="section-head">
          <h2>대한민국 외 인기 여행지</h2>
        </div>
        {!globalDestinations.length && <p className="notice info">도시 데이터 준비중입니다.</p>}
        <ul className="home-scroll-list city-scroll-list">
          {globalDestinations.map((card) => (
            <li key={`global-${card.city}`} className="city-scroll-card">
              <Link to={buildCitySearchHref(card)}>
                {card.image_url ? (
                  <img src={card.image_url} alt={`${card.label} 여행지`} loading="lazy" />
                ) : (
                  <div className="city-thumb-empty">이미지 준비중</div>
                )}
                <div className="city-scroll-meta">
                  <h3>{card.label}</h3>
                  <p>숙소 {card.property_count.toLocaleString()}개</p>
                  <span>{card.highlights ?? "주요 정보 준비중"}</span>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

function currencySymbol(currency: string): string {
  switch (currency.toUpperCase()) {
    case "KRW":
      return "₩";
    case "USD":
      return "$";
    case "JPY":
      return "¥";
    case "EUR":
      return "€";
    default:
      return `${currency.toUpperCase()} `;
  }
}
