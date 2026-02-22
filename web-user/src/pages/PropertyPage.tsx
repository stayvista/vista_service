import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";
import { useLocale } from "../components/locale/LocaleContext";

type PropertyCodeLabel = {
  code: string;
  label: string;
};

type PropertyAmenityGroup = {
  group: string;
  items: PropertyCodeLabel[];
};

type Property = {
  property_id: number;
  name: string;
  city?: string;
  country?: string;
  address1?: string;
  lat?: number;
  lng?: number;
  rating?: number;
  star_rating?: number;
  location_rating?: number;
  review_count?: number;
  district_name?: string;
  property_type_code?: string;
  property_type_label?: string;
  beach_distance_m?: number | null;
  is_beachfront?: boolean;
  kid_free_stay?: boolean;
  popularity_score?: number;
  brand_names?: string[];
  amenity_groups?: PropertyAmenityGroup[];
  payment_options?: PropertyCodeLabel[];
  themes?: PropertyCodeLabel[];
  thumbnail_url?: string | null;
};

type RoomType = {
  room_type_id: number;
  name: string;
  max_guests: number;
  bed_type?: string | null;
  view_type?: string | null;
  bedrooms?: number | null;
  available_rooms?: number | null;
  is_available?: boolean | null;
  base_price: {
    amount: number;
    currency?: string;
  };
};

type PoiNearbyItem = {
  id: number;
  name: string;
  category?: string | null;
  distance_m: number;
  rating_score?: number | null;
  review_count?: number | null;
  preview?: {
    thumbnail_url?: string | null;
    address?: string | null;
    snippet?: string | null;
  } | null;
};

type FxQuote = {
  base: string;
  quote: string;
  rate: number;
  as_of: string;
};

type AmenityGroup = {
  title: string;
  items: string[];
};

type StaycationCard = {
  card_code?: string;
  title: string;
  subtitle?: string | null;
  items: string[];
};

type RoomPlan = {
  planId: string;
  listPrice: number;
  salePrice: number;
  salePriceKrw: number;
  discountPercent: number;
  benefits: string[];
  paySummary: string;
  occupancy: string;
  urgencyText?: string;
};

type RoomAvailabilityTone = "available" | "low" | "soldout";

type RoomAvailability = {
  tone: RoomAvailabilityTone;
  label: string;
  detail: string;
};

type RoomOffer = {
  room: RoomType;
  score: number;
  availability: RoomAvailability;
  isBookable: boolean;
  media: string[];
  specs: string[];
  plans: RoomPlan[];
};

type ReviewCard = {
  reviewer: string;
  score: number;
  title: string;
  body: string;
  stayMonth: string;
  travelerType: string;
  tags: string[];
};

type PropertyReviewSummary = {
  total: number;
  avg_score: number;
  service: number;
  cleanliness: number;
  facility: number;
  value_for_money: number;
  location: number;
};

type PropertyReviewTagCount = {
  tag: string;
  count: number;
};

type PropertyReviewItem = {
  review_id: number;
  reviewer: string;
  score: number;
  title: string;
  body: string;
  stay_month: string;
  traveler_type: string;
  tags: string[];
};

type PropertyReviewData = {
  summary: PropertyReviewSummary;
  tags: PropertyReviewTagCount[];
  items: PropertyReviewItem[];
  meta: {
    page: number;
    size: number;
    has_more: boolean;
    total: number;
  };
};

type PropertyContentData = {
  editorial?: {
    short_description?: string | null;
    long_description?: string | null;
    check_in_time?: string | null;
    check_out_time?: string | null;
    airport_transfer_fee_krw?: number | null;
    breakfast_fee_krw?: number | null;
    remodeled_year?: number | null;
    children_policy?: string | null;
  } | null;
  highlights: string[];
  gallery_images: string[];
  staycation_cards: Array<{
    card_code: string;
    title: string;
    subtitle?: string | null;
    items: string[];
  }>;
  room_content: Array<{
    room_type_id: number;
    media: string[];
    features: string[];
    plans: Array<{
      plan_code: string;
      occupancy_text?: string | null;
      pay_summary?: string | null;
      urgency_text?: string | null;
      list_price_krw: number;
      sale_price_krw: number;
      benefits: string[];
    }>;
  }>;
};

export function PropertyPage() {
  const { id } = useParams();
  const [search] = useSearchParams();
  const navigate = useNavigate();
  const { locale } = useLocale();

  const [property, setProperty] = useState<Property | null>(null);
  const [propertyContent, setPropertyContent] = useState<PropertyContentData | null>(null);
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);
  const [nearby, setNearby] = useState<PoiNearbyItem[]>([]);
  const [reviewData, setReviewData] = useState<PropertyReviewData | null>(null);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [reviewLoadFailed, setReviewLoadFailed] = useState(false);
  const [fxRate, setFxRate] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [holdPendingPlanId, setHoldPendingPlanId] = useState<string | null>(null);
  const [holdErrorMessage, setHoldErrorMessage] = useState<string | null>(null);

  const [expandedDescription, setExpandedDescription] = useState(false);
  const [activeReviewTag, setActiveReviewTag] = useState("all");

  const checkIn = search.get("check_in") ?? "2026-03-02";
  const checkOut = search.get("check_out") ?? "2026-03-03";
  const adults = search.get("adults") ?? "2";
  const children = search.get("children") ?? "0";
  const rooms = search.get("rooms") ?? "1";
  const destinationLabel = search.get("place_label") ?? property?.city ?? "서울";
  const stayNights = useMemo(() => {
    const start = new Date(`${checkIn}T00:00:00`);
    const end = new Date(`${checkOut}T00:00:00`);
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 1;
    return Math.max(1, Math.round((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)));
  }, [checkIn, checkOut]);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    const roomTypeQuery = new URLSearchParams({
      check_in: checkIn,
      check_out: checkOut,
      rooms: String(Math.max(1, Number(rooms) || 1)),
    });

    Promise.all([
      apiGet<Property>(`/v1/properties/${id}`),
      apiGet<{ items: RoomType[] }>(`/v1/properties/${id}/room-types?${roomTypeQuery.toString()}`),
      apiGet<PropertyContentData>(`/v1/properties/${id}/content`),
    ])
      .then(([propertyRes, roomRes, contentRes]) => {
        if (cancelled) return;
        setProperty(propertyRes.data);
        setRoomTypes(roomRes.data.items ?? []);
        setPropertyContent(contentRes.data);
      })
      .catch(() => {
        if (cancelled) return;
        setPropertyContent(null);
        setError("숙소 상세 정보를 불러오지 못했습니다.");
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [checkIn, checkOut, id, rooms]);

  useEffect(() => {
    setActiveReviewTag("all");
  }, [id]);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    const query = new URLSearchParams({
      page: "1",
      size: "12",
    });
    if (activeReviewTag !== "all") {
      query.set("tag", activeReviewTag);
    }

    setReviewLoading(true);
    setReviewLoadFailed(false);
    apiGet<PropertyReviewData>(`/v1/properties/${id}/reviews?${query.toString()}`)
      .then((res) => {
        if (cancelled) return;
        setReviewData(res.data);
      })
      .catch(() => {
        if (cancelled) return;
        setReviewData(null);
        setReviewLoadFailed(true);
      })
      .finally(() => {
        if (cancelled) return;
        setReviewLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [activeReviewTag, id]);

  useEffect(() => {
    if (locale.currency.toUpperCase() === "KRW") {
      setFxRate(1);
      return;
    }
    let cancelled = false;
    apiGet<FxQuote>(`/v1/fx?base=KRW&quote=${encodeURIComponent(locale.currency)}`)
      .then((res) => {
        if (cancelled) return;
        const nextRate = Number(res.data.rate);
        setFxRate(Number.isFinite(nextRate) && nextRate > 0 ? nextRate : 1);
      })
      .catch(() => {
        if (cancelled) return;
        setFxRate(1);
      });

    return () => {
      cancelled = true;
    };
  }, [locale.currency]);

  useEffect(() => {
    if (!property?.lat || !property?.lng) {
      setNearby([]);
      return;
    }
    let cancelled = false;
    const latDelta = 0.08;
    const lngDelta = 0.08 / Math.max(Math.cos((property.lat * Math.PI) / 180), 0.3);
    const bbox = [
      property.lat - latDelta,
      property.lng - lngDelta,
      property.lat + latDelta,
      property.lng + lngDelta,
    ].join(",");
    const query = new URLSearchParams({
      bbox,
      sort: "distance",
      limit: "36",
      center: `${property.lat},${property.lng}`,
      radius_m: "12000",
    });

    apiGet<{ items: PoiNearbyItem[] }>(`/v1/poi/nearby?${query.toString()}`)
      .then((res) => {
        if (cancelled) return;
        setNearby(res.data.items ?? []);
      })
      .catch(() => {
        if (cancelled) return;
        setNearby([]);
      });

    return () => {
      cancelled = true;
    };
  }, [property?.lat, property?.lng]);

  const searchPageLink = useMemo(() => {
    const query = new URLSearchParams({
      place_id: `city:${property?.city ?? "Seoul"}`,
      place_label: destinationLabel,
      city: property?.city ?? "Seoul",
      check_in: checkIn,
      check_out: checkOut,
      adults,
      children,
      rooms,
      currency: locale.currency,
    });
    return `/search?${query.toString()}`;
  }, [adults, checkIn, checkOut, children, destinationLabel, locale.currency, property?.city, rooms]);

  const galleryImages = useMemo(() => {
    const fromContent = (propertyContent?.gallery_images ?? []).filter((item) => item && item.trim().length > 0);
    if (property?.thumbnail_url) {
      return [property.thumbnail_url, ...fromContent.filter((item) => item !== property.thumbnail_url)];
    }
    return fromContent;
  }, [property?.thumbnail_url, propertyContent?.gallery_images]);

  const amenityGroups = useMemo(() => {
    return mapAmenityGroupsFromApi(property?.amenity_groups ?? []);
  }, [property?.amenity_groups]);

  const staycationCards = useMemo<StaycationCard[]>(() => {
    return (propertyContent?.staycation_cards ?? []).map((card) => ({
      card_code: card.card_code,
      title: card.title,
      subtitle: card.subtitle,
      items: card.items ?? [],
    }));
  }, [propertyContent?.staycation_cards]);

  const roomContentByType = useMemo(() => {
    return new Map((propertyContent?.room_content ?? []).map((item) => [item.room_type_id, item]));
  }, [propertyContent?.room_content]);

  const allRoomOffers = useMemo<RoomOffer[]>(() => {
    return roomTypes.map((room) => {
      const content = roomContentByType.get(room.room_type_id);
      const media = content?.media?.length
        ? content.media
        : (property?.thumbnail_url ? [property.thumbnail_url] : []);
      const specs = content?.features?.length
        ? content.features
        : buildRoomSpecsFromType(room);
      const soldOut = room.is_available === false || ((room.available_rooms ?? 0) <= 0 && room.available_rooms != null);
      const lowStock = !soldOut && room.available_rooms != null && room.available_rooms <= 3;
      const availability: RoomAvailability = soldOut
        ? { tone: "soldout", label: "판매 완료", detail: "선택한 일정의 잔여 객실 없음" }
        : lowStock
          ? { tone: "low", label: "마감 임박", detail: `잔여 객실 ${room.available_rooms}개` }
          : room.available_rooms != null
            ? { tone: "available", label: "예약 가능", detail: `잔여 객실 ${room.available_rooms}개` }
            : { tone: "available", label: "예약 가능", detail: "실시간 재고 연동" };

      const plans: RoomPlan[] = (content?.plans ?? []).map((plan, planIndex) => {
        const listPriceKrw = Math.max(0, plan.list_price_krw);
        const salePriceKrw = Math.max(0, plan.sale_price_krw);
        const listPrice = convertFromKrw(listPriceKrw, fxRate, locale.currency);
        const salePrice = convertFromKrw(salePriceKrw, fxRate, locale.currency);
        const discountPercent = listPrice > 0 ? Math.max(0, Math.round((1 - salePrice / listPrice) * 100)) : 0;
        return {
          planId: `${room.room_type_id}-${plan.plan_code}-${planIndex}`,
          listPrice,
          salePrice,
          salePriceKrw,
          discountPercent,
          occupancy: plan.occupancy_text ?? `아동 ${children}명 · 투숙 무료`,
          paySummary: plan.pay_summary ?? "결제 정책 확인",
          urgencyText: plan.urgency_text ?? undefined,
          benefits: plan.benefits ?? [],
        };
      });

      return {
        room,
        score: Number((((property?.rating ?? 4.2) * 2)).toFixed(1)),
        availability,
        isBookable: !soldOut,
        media,
        specs,
        plans,
      };
    });
  }, [children, fxRate, locale.currency, property?.rating, property?.thumbnail_url, roomContentByType, roomTypes]);

  const roomOffers = useMemo<RoomOffer[]>(() => {
    return allRoomOffers;
  }, [allRoomOffers]);

  const bookableRoomOffers = useMemo<RoomOffer[]>(() => {
    return allRoomOffers.filter((offer) => offer.isBookable);
  }, [allRoomOffers]);

  const soldOutRoomTypeCount = useMemo(() => {
    return allRoomOffers.filter((offer) => !offer.isBookable).length;
  }, [allRoomOffers]);

  const roomFilterChips = useMemo(() => {
    const estimatedCount = (ratio: number) => (bookableRoomOffers.length === 0 ? 0 : Math.max(1, Math.floor(bookableRoomOffers.length * ratio)));
    const chips = [
      { label: "금연", count: estimatedCount(0.7) },
      { label: "조식 포함", count: bookableRoomOffers.reduce((acc, room) => acc + room.plans.filter((p) => p.benefits.some((benefit) => benefit.includes("조식"))).length, 0) },
      { label: "후지불 가능", count: bookableRoomOffers.reduce((acc, room) => acc + room.plans.filter((p) => p.paySummary.includes("숙소")).length, 0) },
      {
        label: "예약 무료 취소",
        count: bookableRoomOffers.reduce((acc, room) => acc + room.plans.filter((p) =>
          p.benefits.some((benefit) => benefit.includes("무료 취소"))
          || p.paySummary.includes("무료 취소")
          || (p.urgencyText ?? "").includes("무료 취소")).length, 0),
      },
      { label: "트윈베드", count: estimatedCount(0.45) },
      { label: "마운틴뷰", count: estimatedCount(0.35) },
      { label: "가족 여행객에게 추천", count: estimatedCount(0.25) },
    ];
    return chips.filter((chip) => chip.count > 0);
  }, [bookableRoomOffers]);

  const minPrice = useMemo(() => {
    const candidates = bookableRoomOffers.flatMap((room) => room.plans.map((plan) => plan.salePrice));
    if (candidates.length === 0) return 0;
    return Math.min(...candidates);
  }, [bookableRoomOffers]);

  const ratingOutOfTen = useMemo(() => {
    const fromReviews = reviewData?.summary?.avg_score ?? 0;
    if (fromReviews > 0) {
      return Number(fromReviews.toFixed(1));
    }
    const base = (property?.rating ?? 4.2) * 2;
    return Number(Math.max(7.4, Math.min(9.8, base)).toFixed(1));
  }, [property?.rating, reviewData?.summary?.avg_score]);

  const reviewMetricItems = useMemo(() => {
    const summary = reviewData?.summary;
    if (summary && summary.total > 0) {
      return [
        { label: "서비스", score: clampScore(summary.service) },
        { label: "숙소 청결 상태", score: clampScore(summary.cleanliness) },
        { label: "부대시설", score: clampScore(summary.facility) },
        { label: "가격 대비 만족도", score: clampScore(summary.value_for_money) },
        { label: "위치", score: clampScore(summary.location) },
      ];
    }
    const basis = ratingOutOfTen;
    return [
      { label: "서비스", score: clampScore(basis + 0.5) },
      { label: "숙소 청결 상태", score: clampScore(basis + 0.3) },
      { label: "부대시설", score: clampScore(basis + 0.1) },
      { label: "가격 대비 만족도", score: clampScore(basis - 0.1) },
      { label: "위치", score: clampScore((property?.location_rating ?? property?.rating ?? 4.2) * 2) },
    ];
  }, [property?.location_rating, property?.rating, ratingOutOfTen, reviewData?.summary]);

  const reviewCards = useMemo<ReviewCard[]>(() => {
    return (reviewData?.items ?? []).map((item) => ({
      reviewer: item.reviewer,
      score: item.score,
      title: item.title,
      body: item.body,
      stayMonth: item.stay_month,
      travelerType: item.traveler_type,
      tags: item.tags ?? [],
    }));
  }, [reviewData?.items]);

  const reviewTags = useMemo(() => {
    if (reviewData?.tags?.length) {
      return ["all", ...reviewData.tags.map((item) => item.tag)];
    }
    return ["all"];
  }, [reviewData?.tags]);

  const reviewTagCountMap = useMemo(() => {
    const map = new Map<string, number>();
    if (reviewData?.tags?.length) {
      reviewData.tags.forEach((item) => map.set(item.tag, item.count));
      map.set("all", reviewData.summary.total ?? reviewData.meta.total ?? reviewCards.length);
      return map;
    }

    map.set("all", reviewCards.length);
    return map;
  }, [reviewCards, reviewData?.meta.total, reviewData?.summary.total, reviewData?.tags]);

  const filteredReviewCards = useMemo(() => {
    if (activeReviewTag === "all") {
      return reviewCards;
    }
    return reviewCards.filter((review) => review.tags.includes(activeReviewTag));
  }, [activeReviewTag, reviewCards]);

  const reviewTotal = reviewData?.summary?.total ?? reviewData?.meta?.total ?? property?.review_count ?? filteredReviewCards.length;

  const reviewSummaryText = useMemo(() => {
    if (!reviewMetricItems.length) {
      return {
        positive: "리뷰 데이터가 아직 충분하지 않습니다.",
        caution: "리뷰 누적 후 장단점 요약을 더 정확하게 제공합니다.",
      };
    }
    const sorted = [...reviewMetricItems].sort((a, b) => b.score - a.score);
    const strengths = sorted.slice(0, 2).map((item) => item.label);
    const caution = sorted[sorted.length - 1];
    return {
      positive: `리뷰 기준 강점은 ${strengths.join(", ")}입니다.`,
      caution: `상대적으로 ${caution.label} 항목은 숙소/객실 타입에 따라 체감 차이가 있을 수 있습니다.`,
    };
  }, [reviewMetricItems]);

  const popularSpots = useMemo(() => {
    return nearby.slice(0, 12);
  }, [nearby]);

  const closeSpots = useMemo(() => {
    return nearby.filter((item) => item.distance_m <= 1300).slice(0, 10);
  }, [nearby]);

  const checkoutBaseQuery = useMemo(
    () => ({
      check_in: checkIn,
      check_out: checkOut,
      adults,
      children,
      rooms,
    }),
    [adults, checkIn, checkOut, children, rooms],
  );

  function toApiError(value: unknown): { code?: string; message?: string } {
    if (typeof value === "object" && value !== null) {
      return value as { code?: string; message?: string };
    }
    return {};
  }

  async function handleBookNow(offer: RoomOffer, plan: RoomPlan) {
    if (!offer.isBookable || !property) return;
    setHoldErrorMessage(null);
    setHoldPendingPlanId(plan.planId);
    try {
      const payload = {
        room_type_id: offer.room.room_type_id,
        check_in: checkIn,
        check_out: checkOut,
        rooms: Math.max(1, Number(rooms) || 1),
        guests: {
          adults: Math.max(1, Number(adults) || 1),
          children: Math.max(0, Number(children) || 0),
        },
        price: {
          currency: "KRW",
          amount_total: Math.max(0, plan.salePriceKrw * stayNights * Math.max(1, Number(rooms) || 1)),
        },
      };
      const hold = await apiPost<{ booking_id: string; expires_at: string }>(
        "/v1/bookings/holds",
        payload,
        { "Idempotency-Key": crypto.randomUUID() },
      );
      const params = new URLSearchParams({
        property_id: String(property.property_id),
        property_name: property.name,
        room_type_id: String(offer.room.room_type_id),
        room_name: offer.room.name,
        check_in: checkoutBaseQuery.check_in,
        check_out: checkoutBaseQuery.check_out,
        adults: checkoutBaseQuery.adults,
        children: checkoutBaseQuery.children,
        rooms: checkoutBaseQuery.rooms,
        booking_id: hold.data.booking_id,
        expires_at: hold.data.expires_at,
      });
      navigate(`/checkout/booking?${params.toString()}`);
    } catch (e) {
      const err = toApiError(e);
      const soldOutByCode = err.code === "BOOKING_OVERBOOKED";
      const soldOutByMessage = (err.message ?? "").toLowerCase().includes("inventory");
      if (soldOutByCode || soldOutByMessage) {
        setRoomTypes((prev) => prev.map((room) => (
          room.room_type_id === offer.room.room_type_id
            ? { ...room, is_available: false, available_rooms: 0 }
            : room
        )));
        setHoldErrorMessage("방금 다른 고객이 먼저 결제를 완료해 선택한 객실이 마감되었습니다. 다른 객실을 선택해 주세요.");
      } else {
        setHoldErrorMessage("예약 준비 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setHoldPendingPlanId(null);
    }
  }

  if (loading) {
    return (
      <section className="page property-v3-page">
        <p className="notice info">숙소 상세 정보를 불러오는 중입니다...</p>
      </section>
    );
  }

  if (error) {
    return (
      <section className="page property-v3-page">
        <p className="notice error">{error}</p>
      </section>
    );
  }

  if (!property) {
    return (
      <section className="page property-v3-page">
        <p className="notice warning">숙소 정보를 찾을 수 없습니다.</p>
      </section>
    );
  }

  const editorial = propertyContent?.editorial;
  const descriptionText = (editorial?.long_description ?? editorial?.short_description ?? "").trim() || `${property.name} 소개 정보 준비중입니다.`;
  const shortDescription = (editorial?.short_description ?? "").trim() || descriptionText.slice(0, 220);
  const shouldTrimDescription = descriptionText.length > 220 && !editorial?.short_description;
  const checkInTime = editorial?.check_in_time ?? "15:00";
  const checkOutTime = editorial?.check_out_time ?? "11:00";
  const airportTransferFee = editorial?.airport_transfer_fee_krw != null
    ? formatMoney(convertFromKrw(editorial.airport_transfer_fee_krw, fxRate, locale.currency), locale.currency)
    : "정보 없음";
  const breakfastFee = editorial?.breakfast_fee_krw != null
    ? formatMoney(convertFromKrw(editorial.breakfast_fee_krw, fxRate, locale.currency), locale.currency)
    : "정보 없음";
  const remodeledYear = editorial?.remodeled_year ? `${editorial.remodeled_year}` : "정보 없음";
  const childrenPolicy = editorial?.children_policy || (property.kid_free_stay ? "아동 무료 투숙 가능" : "아동 동반 가능");
  const highlights = propertyContent?.highlights ?? [];

  return (
    <section className="page property-v3-page">
      <header className="property-top-searchbar">
        <div className="property-top-search-grid">
          <div className="property-top-field">
            <span>목적지</span>
            <strong>{destinationLabel}</strong>
          </div>
          <div className="property-top-field">
            <span>체크인</span>
            <strong>{formatDate(checkIn)}</strong>
          </div>
          <div className="property-top-field">
            <span>체크아웃</span>
            <strong>{formatDate(checkOut)}</strong>
          </div>
          <div className="property-top-field">
            <span>투숙 인원</span>
            <strong>객실 {rooms}개 · 성인 {adults} · 아동 {children}</strong>
          </div>
          <Link className="property-top-cta" to={searchPageLink}>확인</Link>
        </div>
      </header>

      <p className="property-v3-breadcrumb">
        홈 / {property.country ?? "대한민국"} / {property.city ?? "도시 미지정"} / {property.name}
      </p>

      <section className="property-v3-header">
        <div className="property-v3-title-group">
          <h2>{property.name}</h2>
          <p>{property.address1 ?? "주소 정보 준비 중"} · {property.city ?? "도시 미지정"}</p>
          <div className="property-v3-metachips">
            <span className="chip-btn active">{ratingOutOfTen.toFixed(1)} 우수</span>
            <span className="chip-btn">{property.star_rating ?? deriveStar(property.rating)}성급</span>
            <span className="chip-btn">리뷰 {(property.review_count ?? 0).toLocaleString()}건</span>
            {property.district_name && <span className="chip-btn">{property.district_name}</span>}
            {property.property_type_label && <span className="chip-btn">{property.property_type_label}</span>}
            {(property.brand_names ?? []).map((brand) => (
              <span key={brand} className="chip-btn">{brand}</span>
            ))}
          </div>
        </div>
        <aside className="property-v3-pricecard">
          <p>시작가</p>
          <strong>{formatMoney(minPrice, locale.currency)}</strong>
          <span>1박당 총 금액 · 세금/수수료 포함</span>
          <a href="#rooms" className="inline-cta">객실 상품 보기</a>
        </aside>
      </section>

      <section className="property-v3-gallery" aria-label="숙소 이미지 갤러리">
        <figure className="property-v3-gallery-main">
          <img src={galleryImages[0]} alt={`${property.name} 대표 이미지`} loading="lazy" />
          <button type="button" className="property-photo-btn">객실 사진 보기</button>
        </figure>
        {galleryImages.slice(1, 6).map((image, index) => (
          <figure key={`${property.property_id}-gallery-${index}`} className="property-v3-gallery-tile">
            <img src={image} alt={`${property.name} 이미지 ${index + 2}`} loading="lazy" />
          </figure>
        ))}
      </section>

      <nav className="property-v3-tabs" aria-label="상세 탭">
        <a href="#overview">개요</a>
        <a href="#rooms">객실</a>
        <a href="#amenities">편의 시설/서비스</a>
        <a href="#reviews">이용후기</a>
        <a href="#location">위치</a>
        <a href="#policy">정책</a>
      </nav>

      <div className="property-v3-layout">
        <main className="property-v3-main">
          <section id="overview" className="property-section-card">
            <h3>{property.name} 소개</h3>
            <p>
              {expandedDescription || !shouldTrimDescription ? descriptionText : `${shortDescription}...`}
            </p>
            {shouldTrimDescription && (
              <button
                type="button"
                className="inline-ghost"
                onClick={() => setExpandedDescription((prev) => !prev)}
              >
                {expandedDescription ? "접기" : "더 보기"}
              </button>
            )}

            <div className="property-quick-facts">
              <div>
                <strong>체크인/체크아웃</strong>
                <span>{checkInTime} / {checkOutTime}</span>
              </div>
              <div>
                <strong>아동 정책</strong>
                <span>{childrenPolicy}</span>
              </div>
              <div>
                <strong>해변 근접</strong>
                <span>{property.is_beachfront ? "전용 해변" : `${formatDistance(property.beach_distance_m ?? 4200)} 거리`}</span>
              </div>
              <div>
                <strong>인기 지수</strong>
                <span>{Math.max(1, property.popularity_score ?? 0).toLocaleString()}</span>
              </div>
            </div>
          </section>

          <section className="property-section-card">
            <h3>주요 특징</h3>
            {highlights.length > 0 ? (
              <ul className="property-feature-list">
                {highlights.map((item, index) => (
                  <li key={`${property.property_id}-highlight-${index}`}>{item}</li>
                ))}
              </ul>
            ) : (
              <p className="notice info">등록된 주요 특징이 없습니다.</p>
            )}
          </section>

          <section className="property-section-card">
            <h3>이용 가능한 스테이케이션 상품</h3>
            <p className="section-helper">숙박하는 동안 특별한 혜택을 누리세요.</p>
            <div className="staycation-grid">
              {staycationCards.map((card) => (
                <article key={card.title} className="staycation-card">
                  <h4>{card.title}</h4>
                  <p>{card.subtitle}</p>
                  <ul>
                    {card.items.map((item) => (
                      <li key={`${card.title}-${item}`}>{item}</li>
                    ))}
                  </ul>
                </article>
              ))}
            </div>
            {staycationCards.length === 0 && (
              <p className="notice info">스테이케이션 상품 정보가 없습니다.</p>
            )}
          </section>

          <section id="amenities" className="property-section-card">
            <div className="section-headline">
              <h3>숙소 편의 시설/서비스</h3>
              <span className="section-score-pill">최고 {reviewMetricItems[2]?.score.toFixed(1) ?? "8.8"}</span>
            </div>
            <div className="property-amenity-columns">
              {amenityGroups.map((group) => (
                <article key={group.title} className="amenity-column">
                  <h4>{group.title}</h4>
                  <ul>
                    {group.items.map((item) => (
                      <li key={`${group.title}-${item}`}>{item}</li>
                    ))}
                  </ul>
                </article>
              ))}
            </div>
          </section>

          <section id="rooms" className="property-section-card">
            <div className="section-headline">
              <h3>객실을 선택하세요</h3>
              <strong>
                객실 종류 {roomOffers.length}개 · 예약 가능 {bookableRoomOffers.length}개
                {soldOutRoomTypeCount > 0 ? ` · 판매 완료 ${soldOutRoomTypeCount}개` : ""}
              </strong>
            </div>
            <p className="section-helper">
              원하는 숙박 날짜까지 아직 시간이 많이 남았습니다. 예약 무료 취소 가능한 상품을 선택해 만약의 변경에 대비하세요.
            </p>
            {holdErrorMessage && <p className="notice warning">{holdErrorMessage}</p>}
            {soldOutRoomTypeCount > 0 && (
              <p className="notice warning">
                선택한 일정에서 판매 완료된 객실은 목록에서 "판매 완료" 상태로 표시됩니다.
              </p>
            )}
            <div className="room-filter-chip-row">
              {roomFilterChips.map((chip) => (
                <span key={chip.label} className="chip-btn">{chip.label} ({chip.count})</span>
              ))}
            </div>

            <div className="room-block-list">
              {roomOffers.map((offer) => (
                <article key={offer.room.room_type_id} className="room-block-card">
                  <header className="room-block-head">
                    <div>
                      <h4>{offer.room.name}</h4>
                      <p className={`room-availability-label ${offer.availability.tone}`}>
                        {offer.availability.label} · {offer.availability.detail}
                      </p>
                    </div>
                    <div className="room-block-score">우수 {offer.score.toFixed(1)}</div>
                  </header>
                  <div className="room-block-body">
                    <aside className="room-media-col">
                      {offer.media.length > 0 ? (
                        <>
                          <img src={offer.media[0]} alt={`${offer.room.name} 대표 이미지`} loading="lazy" />
                          <div className="room-media-strip">
                            {offer.media.slice(1).map((image, index) => (
                              <img
                                key={`${offer.room.room_type_id}-media-${index}`}
                                src={image}
                                alt={`${offer.room.name} 이미지 ${index + 2}`}
                                loading="lazy"
                              />
                            ))}
                          </div>
                        </>
                      ) : (
                        <p className="notice info">객실 이미지가 없습니다.</p>
                      )}
                      <ul className="room-spec-list">
                        {offer.specs.map((spec) => (
                          <li key={`${offer.room.room_type_id}-${spec}`}>{spec}</li>
                        ))}
                      </ul>
                    </aside>

                    <div className="room-plan-col">
                      {offer.plans.map((plan) => (
                        <article
                          key={plan.planId}
                          className={`room-plan-row${offer.isBookable ? "" : " is-sold-out"}`}
                        >
                          <div className="room-plan-benefits">
                            <ul>
                              {plan.benefits.map((benefit) => (
                                <li key={`${plan.planId}-${benefit}`}>{benefit}</li>
                              ))}
                            </ul>
                            <p className="room-plan-meta">{plan.occupancy} · {plan.paySummary}</p>
                          </div>
                          <div className="room-plan-price">
                            <span className="room-plan-discount">{plan.discountPercent}% 할인</span>
                            <p className="room-price-list">{formatMoney(plan.listPrice, locale.currency)}</p>
                            <strong>{formatMoney(plan.salePrice, locale.currency)}</strong>
                            <small>1박당 총 금액 · 세금 및 수수료 포함 요금</small>
                            <p className="room-plan-monthly">최저 월 {formatMoney(Math.max(1, Math.round(plan.salePrice / 5)), locale.currency)} 할부 결제</p>
                          </div>
                          <div className="room-plan-cta">
                            <span className="room-plan-qty">{offer.isBookable ? "1" : "-"}</span>
                            {offer.isBookable ? (
                              <button
                                type="button"
                                className="inline-cta"
                                onClick={() => void handleBookNow(offer, plan)}
                                disabled={holdPendingPlanId === plan.planId}
                              >
                                {holdPendingPlanId === plan.planId ? "재고 확인 중..." : "지금 예약하기"}
                              </button>
                            ) : (
                              <button type="button" className="inline-cta is-disabled" disabled>
                                판매 완료
                              </button>
                            )}
                            <em className={`room-plan-status ${offer.availability.tone}`}>
                              {offer.isBookable
                                ? offer.availability.detail
                                : "선택한 일정 판매 완료"}
                            </em>
                          </div>
                        </article>
                      ))}
                      {offer.plans.length === 0 && (
                        <p className="notice info">요금 플랜 정보가 없습니다.</p>
                      )}
                    </div>
                  </div>
                </article>
              ))}
            </div>

            {roomOffers.length === 0 && (
              <p className="notice warning">객실 정보가 없습니다. 다른 숙소를 확인해 주세요.</p>
            )}
            {roomOffers.length > 0 && bookableRoomOffers.length === 0 && (
              <p className="notice warning">현재 예약 가능한 객실이 없습니다. 날짜를 변경해서 다시 확인해 주세요.</p>
            )}
          </section>

          <section id="reviews" className="property-section-card">
            <h3>{property.name} 실제 투숙객 작성 이용후기</h3>
            <div className="review-overview-grid">
              <article className="review-score-card">
                <div className="review-score-main">
                  <strong>{ratingOutOfTen.toFixed(1)}</strong>
                  <span>/10</span>
                </div>
                <p>우수</p>
                <p>이용후기 {reviewTotal.toLocaleString()}건의 이용 후기</p>
              </article>

              <article className="review-metrics-card">
                {reviewMetricItems.map((metric) => (
                  <div key={metric.label} className="review-metric-row">
                    <span>{metric.label}</span>
                    <div className="review-metric-bar"><i style={{ width: `${metric.score * 10}%` }} /></div>
                    <strong>{metric.score.toFixed(1)}</strong>
                  </div>
                ))}
              </article>
            </div>

            <article className="review-summary-box">
              <h4>이용후기 요약</h4>
              <p>{reviewSummaryText.positive}</p>
              <p>{reviewSummaryText.caution}</p>
              {reviewLoadFailed && (
                <p className="review-inline-hint">리뷰 API 응답이 불안정해 저장된 기본 요약으로 표시 중입니다.</p>
              )}
            </article>

            <div className="review-filter-chips">
              {reviewTags.map((tag) => (
                <button
                  key={tag}
                  type="button"
                  className={tag === activeReviewTag ? "chip-btn active" : "chip-btn"}
                  onClick={() => setActiveReviewTag(tag)}
                >
                  {tag === "all" ? "All Reviews" : tag} ({reviewTagCountMap.get(tag) ?? 0})
                </button>
              ))}
            </div>

            <div className="review-list-v3">
              {reviewLoading && <p className="notice info">이용후기를 불러오는 중입니다...</p>}
              {filteredReviewCards.map((review, index) => (
                <article key={`${review.reviewer}-${index}`} className="review-item-v3">
                  <aside>
                    <strong>{review.score.toFixed(1)}</strong>
                    <p>{review.reviewer}</p>
                    <p>{review.travelerType}</p>
                    <p>{review.stayMonth} 1박</p>
                  </aside>
                  <div>
                    <h4>“{review.title}”</h4>
                    <p>{review.body}</p>
                    <div className="review-tag-wrap">
                      {review.tags.map((tag) => (
                        <span key={`${review.reviewer}-${tag}`} className="chip-btn">{tag}</span>
                      ))}
                    </div>
                  </div>
                </article>
              ))}
              {!reviewLoading && filteredReviewCards.length === 0 && (
                <p className="notice warning">조건에 맞는 이용후기가 없습니다.</p>
              )}
            </div>
          </section>
        </main>

        <aside id="location" className="property-v3-side">
          <section className="property-section-card side-score-card sticky">
            <h3>{((property.location_rating ?? property.rating ?? 0) * 2).toFixed(1)} 우수</h3>
            <p>숙소 위치 평가 점수</p>
            <p>{property.district_name ?? `${property.city ?? "도심"} 중심`} · 도심까지 {formatDistance(Math.max(200, property.beach_distance_m ?? 6400))}</p>
            {property.lat != null && property.lng != null && (
              <a
                className="inline-ghost"
                href={`https://www.google.com/maps?q=${property.lat},${property.lng}`}
                target="_blank"
                rel="noreferrer"
              >
                지도에서 보기
              </a>
            )}
            {(property.payment_options ?? []).length > 0 && (
              <div className="side-mini-group">
                <h4>결제 옵션</h4>
                <ul>
                  {property.payment_options?.slice(0, 5).map((item) => (
                    <li key={item.code}>{item.label}</li>
                  ))}
                </ul>
              </div>
            )}
            {(property.themes ?? []).length > 0 && (
              <div className="side-mini-group">
                <h4>여행 테마</h4>
                <div className="side-chip-wrap">
                  {property.themes?.map((theme) => (
                    <span key={theme.code} className="chip-btn">{theme.label}</span>
                  ))}
                </div>
              </div>
            )}
          </section>

          <section className="property-section-card map-mini-card">
            <h4>인기 명소</h4>
            <ul className="spot-list-v3">
              {popularSpots.map((spot) => (
                <li key={`popular-${spot.id}`}>
                  <span>{spot.name}</span>
                  <em>{formatDistance(spot.distance_m)}</em>
                </li>
              ))}
            </ul>
          </section>

          <section className="property-section-card map-mini-card">
            <h4>숙소 인근 명소</h4>
            <ul className="spot-list-v3">
              {closeSpots.map((spot) => (
                <li key={`near-${spot.id}`}>
                  <span>{spot.name}</span>
                  <em>{formatDistance(spot.distance_m)}</em>
                </li>
              ))}
            </ul>
          </section>

          <section id="policy" className="property-section-card map-mini-card">
            <h4>유용한 정보</h4>
            <ul className="policy-list">
              <li>체크인 시작시간: {checkInTime}</li>
              <li>체크아웃 마감시간: {checkOutTime}</li>
              <li>공항 이동 교통편 서비스 요금: {airportTransferFee}</li>
              <li>조식 요금(객실 요금 별도): {breakfastFee}</li>
              <li>최근 리모델링 년도: {remodeledYear}</li>
            </ul>
          </section>
        </aside>
      </div>
    </section>
  );
}

function deriveStar(rating?: number): number {
  if (!rating) return 4;
  return Math.max(3, Math.min(5, Math.round(rating)));
}

function convertFromKrw(amountKrw: number, rate: number, currency: string): number {
  if (currency.toUpperCase() === "KRW") return amountKrw;
  return Math.max(0, Math.round(amountKrw * rate));
}

function formatMoney(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat("ko-KR", {
      style: "currency",
      currency: currency.toUpperCase(),
      maximumFractionDigits: 0,
    }).format(amount);
  } catch {
    return `${currency.toUpperCase()} ${amount.toLocaleString()}`;
  }
}

function formatDistance(distanceM: number): string {
  if (distanceM < 1000) {
    return `${distanceM}m`;
  }
  return `${(distanceM / 1000).toFixed(1)}km`;
}

function formatDate(raw: string): string {
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return raw;
  const y = date.getFullYear();
  const m = date.getMonth() + 1;
  const d = date.getDate();
  return `${y}년 ${m}월 ${d}일`;
}

function buildRoomSpecsFromType(room: RoomType): string[] {
  const specs = [`최대 투숙 ${room.max_guests}인`];
  if (room.bedrooms && room.bedrooms > 0) {
    specs.push(`침실 ${room.bedrooms}개`);
  }
  if (room.bed_type) {
    specs.push(`침대 유형 ${room.bed_type}`);
  }
  if (room.view_type) {
    specs.push(`전망 ${room.view_type}`);
  }
  return specs;
}

function mapAmenityGroupsFromApi(groups: PropertyAmenityGroup[]): AmenityGroup[] {
  if (!groups.length) return [];

  return groups.map((group) => ({
    title: amenityGroupTitle(group.group),
    items: group.items.map((item) => item.label),
  }));
}

function amenityGroupTitle(groupCode: string): string {
  switch (groupCode) {
    case "service_option":
      return "이용 가능한 서비스/옵션";
    case "property_facility":
      return "숙소 편의 시설/서비스";
    case "room_facility":
      return "객실 편의 시설/서비스";
    case "view":
      return "전망/뷰";
    case "essential":
      return "필수 편의";
    default:
      return groupCode;
  }
}

function clampScore(value: number): number {
  return Math.max(7, Math.min(9.8, Number(value.toFixed(1))));
}
