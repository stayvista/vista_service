import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";
import { useLocale } from "../components/locale/LocaleContext";
import { HeroSearchBox } from "../components/search/HeroSearchBox";
import { getStaySearchInput, inferCityFromPlaceId, setStaySearchParams } from "../components/search/searchState";
import { StaySearchInput } from "../components/search/searchTypes";

type SearchItem = {
  property_id: number;
  name: string;
  city?: string;
  district?: string;
  price_min: number;
  currency?: string;
  rating: number;
  location_rating?: number;
  star_rating?: number;
  review_count?: number;
  distance_m?: number;
  thumbnail_url?: string | null;
};

type SearchMeta = {
  total: number;
  took_ms: number;
  page: number;
  size: number;
  currency: string;
};

type FacetCountItem = {
  key: string;
  label: string;
  count: number;
};

type SearchFacetData = {
  popular_filters: Array<{ key: string; value: string; label: string; count: number }>;
  districts: Array<{ id: number; name: string; blurb?: string; count: number }>;
  nearby_attractions: Array<{ poi_id: number; name: string; count: number }>;
  brands: FacetCountItem[];
  amenity_groups: Array<{ group: string; items: FacetCountItem[] }>;
  stars: FacetCountItem[];
  property_types: FacetCountItem[];
  payment_options: FacetCountItem[];
  themes: FacetCountItem[];
  amenities: FacetCountItem[];
  guest_rating_bands: FacetCountItem[];
  location_rating_bands: FacetCountItem[];
  distance_bands: FacetCountItem[];
  bed_types: FacetCountItem[];
  bedrooms: FacetCountItem[];
  family_options: FacetCountItem[];
  beach_options: FacetCountItem[];
};

type SearchResponse = {
  items: SearchItem[];
  facets?: SearchFacetData;
  meta?: SearchMeta;
  next_cursor?: string;
};

type SearchCopilotFilter = {
  key: string;
  value: string;
  label: string;
  reason?: string;
};

type SearchCopilotData = {
  recommended_filters: SearchCopilotFilter[];
  explanation: string;
  llm_used: boolean;
  degraded: boolean;
};

type ApiError = { code?: string; message?: string };

const MULTI_VALUE_FILTERS = new Set([
  "stars",
  "amenities",
  "property_type",
  "districts",
  "payment_options",
  "themes",
  "brands",
  "nearby_attractions",
  "bed_types",
  "guest_rating_bands",
  "location_rating_bands",
  "distance_bands",
  "family_options",
  "beach_options",
]);

const CLEARABLE_FILTER_KEYS = [
  "min_price",
  "max_price",
  "min_rating",
  "min_guest_rating",
  "min_location_rating",
  "max_distance_m",
  "stars",
  "amenities",
  "property_type",
  "districts",
  "payment_options",
  "themes",
  "brands",
  "nearby_attractions",
  "bed_types",
  "guest_rating_bands",
  "location_rating_bands",
  "distance_bands",
  "family_options",
  "beach_options",
  "bedrooms",
  "sort",
  "page",
  "size",
];

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const { locale } = useLocale();

  const [items, setItems] = useState<SearchItem[]>([]);
  const [meta, setMeta] = useState<SearchMeta | null>(null);
  const [facets, setFacets] = useState<SearchFacetData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [copilot, setCopilot] = useState<SearchCopilotData | null>(null);
  const [copilotLoading, setCopilotLoading] = useState(false);
  const [copilotError, setCopilotError] = useState<string | null>(null);
  const [showCopilotReasons, setShowCopilotReasons] = useState(false);

  useEffect(() => {
    const next = new URLSearchParams(params);
    let changed = false;

    if (!next.get("place_id") && !next.get("city")) {
      next.set("place_id", "city:Seoul");
      next.set("place_label", "서울");
      next.set("city", "Seoul");
      changed = true;
    }
    if (!next.get("check_in")) {
      next.set("check_in", addDays(7));
      changed = true;
    }
    if (!next.get("check_out")) {
      next.set("check_out", addDays(9));
      changed = true;
    }
    if (!next.get("rooms")) {
      next.set("rooms", "1");
      changed = true;
    }
    if (!next.get("adults")) {
      next.set("adults", "2");
      changed = true;
    }
    if (!next.get("children")) {
      next.set("children", "0");
      changed = true;
    }
    if (next.get("currency") !== locale.currency) {
      next.set("currency", locale.currency);
      changed = true;
    }
    if (!next.get("sort")) {
      next.set("sort", "best_match");
      changed = true;
    }
    if (!next.get("size")) {
      next.set("size", "20");
      changed = true;
    }
    if (!next.get("page")) {
      next.set("page", "1");
      changed = true;
    }

    if (changed) {
      setParams(next, { replace: true });
    }
  }, [locale.currency, params, setParams]);

  const stayInput = useMemo(() => getStaySearchInput(params, locale.currency), [locale.currency, params]);

  const requestQuery = useMemo(() => {
    const next = new URLSearchParams(params);
    next.delete("place_label");
    next.delete("cursor");

    if (next.get("place_id") && !next.get("city")) {
      const city = inferCityFromPlaceId(next.get("place_id"));
      if (city) {
        next.set("city", city);
      }
    }

    return next;
  }, [params]);

  const requestQueryString = useMemo(() => requestQuery.toString(), [requestQuery]);

  const facetQueryString = useMemo(() => {
    const query = new URLSearchParams();
    const placeId = params.get("place_id");
    const city = params.get("city");
    if (placeId) {
      query.set("place_id", placeId);
    } else if (city) {
      query.set("city", city);
    }
    return query.toString();
  }, [params]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);

    Promise.all([
      apiGet<SearchResponse>(`/v1/search/properties?${requestQueryString}`, {}, controller.signal),
      apiGet<SearchFacetData>(`/v1/search/facets?${facetQueryString}`, {}, controller.signal),
    ])
      .then(([searchRes, facetRes]) => {
        setItems(searchRes.data.items ?? []);
        setMeta(searchRes.data.meta ?? null);
        setFacets(searchRes.data.facets ?? facetRes.data);
      })
      .catch((e: unknown) => {
        if ((e as Error).name === "AbortError") {
          return;
        }
        const err = e as ApiError;
        setItems([]);
        setMeta(null);
        setFacets(null);
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "검색 실패"}`);
      })
      .finally(() => {
        setLoading(false);
      });

    return () => controller.abort();
  }, [facetQueryString, requestQueryString]);

  useEffect(() => {
    if (!facets) {
      setCopilot(null);
      return;
    }

    const timer = window.setTimeout(() => {
      setCopilotLoading(true);
      setCopilotError(null);

      const body = {
        place_id: params.get("place_id") ?? null,
        check_in: params.get("check_in") ?? null,
        check_out: params.get("check_out") ?? null,
        guests: {
          rooms: Number(params.get("rooms") ?? "1"),
          adults: Number(params.get("adults") ?? "2"),
          children: Number(params.get("children") ?? "0"),
          children_ages: parseCsvValues(params.get("children_ages")),
        },
        current_filters: collectCurrentFilters(params),
        facets_summary: facets,
        top_results_summary: items.slice(0, 6).map((item) => ({
          property_id: item.property_id,
          city: item.city,
          district: item.district,
          rating: item.rating,
          star_rating: item.star_rating,
          price_min: item.price_min,
        })),
      };

      apiPost<SearchCopilotData>("/v1/ai/search/copilot", body)
        .then((res) => {
          setCopilot(res.data);
        })
        .catch((e: unknown) => {
          const err = e as ApiError;
          setCopilot(null);
          setCopilotError(err.message ?? "AI 추천을 불러오지 못했습니다.");
        })
        .finally(() => {
          setCopilotLoading(false);
        });
    }, 260);

    return () => window.clearTimeout(timer);
  }, [facets, items, params]);

  const facetLabelMap = useMemo(() => {
    const map = new Map<string, string>();
    const register = (param: string, rows: FacetCountItem[] = []) => {
      rows.forEach((row) => {
        map.set(`${param}:${row.key}`, row.label);
      });
    };

    if (facets) {
      register("stars", facets.stars);
      register("property_type", facets.property_types);
      register("amenities", facets.amenities);
      register("brands", facets.brands);
      register("payment_options", facets.payment_options);
      register("themes", facets.themes);
      register("guest_rating_bands", facets.guest_rating_bands);
      register("location_rating_bands", facets.location_rating_bands);
      register("distance_bands", facets.distance_bands);
      register("bed_types", facets.bed_types);
      register("bedrooms", facets.bedrooms);
      register("family_options", facets.family_options);
      register("beach_options", facets.beach_options);
      facets.districts.forEach((district) => {
        map.set(`districts:${district.name}`, district.name);
      });
      facets.nearby_attractions.forEach((poi) => {
        map.set(`nearby_attractions:${poi.poi_id}`, poi.name);
      });
    }
    return map;
  }, [facets]);

  const activeFilterChips = useMemo(() => {
    const chips: Array<{ key: string; value?: string; label: string }> = [];

    if (params.get("place_label")) {
      chips.push({ key: "place_id", label: `위치: ${params.get("place_label")}` });
    }

    const pairs: Array<[string, string]> = [
      ["min_price", "최소 가격"],
      ["max_price", "최대 가격"],
      ["min_rating", "최소 평점"],
      ["min_guest_rating", "게스트 평점"],
      ["min_location_rating", "위치 평점"],
      ["max_distance_m", "중심지 거리"],
      ["bedrooms", "침실 수"],
      ["sort", "정렬"],
    ];

    pairs.forEach(([key, label]) => {
      const value = params.get(key);
      if (!value) {
        return;
      }
      chips.push({ key, label: `${label}: ${value}` });
    });

    [
      "stars",
      "property_type",
      "districts",
      "amenities",
      "brands",
      "payment_options",
      "themes",
      "nearby_attractions",
      "bed_types",
      "guest_rating_bands",
      "location_rating_bands",
      "distance_bands",
      "family_options",
      "beach_options",
    ].forEach((key) => {
      const values = parseCsvValues(params.get(key));
      values.forEach((value) => {
        const facetLabel = facetLabelMap.get(`${key}:${value}`) ?? value;
        chips.push({ key, value, label: `${labelForFilterKey(key)}: ${facetLabel}` });
      });
    });

    return chips;
  }, [facetLabelMap, params]);

  const page = Number(params.get("page") ?? "1") || 1;
  const size = Number(params.get("size") ?? "20") || 20;
  const total = meta?.total ?? items.length;
  const hasMore = page * size < total;
  const amenityGroupMap = useMemo(() => {
    const map = new Map<string, FacetCountItem[]>();
    (facets?.amenity_groups ?? []).forEach((group) => {
      map.set(group.group, group.items ?? []);
    });
    return map;
  }, [facets]);

  const serviceOptionAmenities = amenityGroupMap.get("service_option") ?? [];
  const propertyFacilityAmenities = amenityGroupMap.get("property_facility") ?? [];
  const roomFacilityAmenities = amenityGroupMap.get("room_facility") ?? [];

  function updateParams(
    mutate: (next: URLSearchParams) => void,
    options: { resetPage?: boolean; replace?: boolean } = { resetPage: true },
  ) {
    const next = new URLSearchParams(params);
    mutate(next);
    if (options.resetPage !== false) {
      next.set("page", "1");
    }
    setParams(next, { replace: options.replace });
  }

  function upsertSingle(name: string, value: string) {
    updateParams((next) => {
      const trimmed = value.trim();
      if (trimmed) {
        next.set(name, trimmed);
      } else {
        next.delete(name);
      }
    });
  }

  function toggleMulti(name: string, value: string, checked: boolean) {
    updateParams((next) => {
      const current = parseCsvValues(next.get(name));
      const set = new Set(current);
      if (checked) {
        set.add(value);
      } else {
        set.delete(value);
      }
      const merged = Array.from(set);
      if (merged.length > 0) {
        next.set(name, merged.join(","));
      } else {
        next.delete(name);
      }
    });
  }

  function removeFilterChip(key: string, value?: string) {
    updateParams((next) => {
      if (!value || !MULTI_VALUE_FILTERS.has(key)) {
        next.delete(key);
        if (key === "place_id") {
          next.delete("place_id");
          next.delete("place_label");
        }
        return;
      }
      const current = parseCsvValues(next.get(key));
      const remain = current.filter((item) => item !== value);
      if (remain.length > 0) {
        next.set(key, remain.join(","));
      } else {
        next.delete(key);
      }
    });
  }

  function applyCopilotFilter(filter: SearchCopilotFilter) {
    updateParams((next) => {
      if (MULTI_VALUE_FILTERS.has(filter.key)) {
        const values = parseCsvValues(next.get(filter.key));
        const merged = Array.from(new Set([...values, filter.value]));
        next.set(filter.key, merged.join(","));
      } else {
        next.set(filter.key, filter.value);
      }
    });
  }

  function clearFilters() {
    updateParams((next) => {
      CLEARABLE_FILTER_KEYS.forEach((key) => next.delete(key));
      next.set("sort", "best_match");
      next.set("size", "20");
    });
  }

  function applySearch(nextSearch: StaySearchInput) {
    const base = new URLSearchParams(params);
    CLEARABLE_FILTER_KEYS.forEach((key) => base.delete(key));
    const next = setStaySearchParams(base, nextSearch);
    next.set("sort", "best_match");
    next.set("size", "20");
    next.set("page", "1");
    setParams(next);
  }

  function goNextPage() {
    updateParams(
      (next) => {
        next.set("page", String(page + 1));
      },
      { resetPage: false },
    );
  }

  function goPrevPage() {
    updateParams(
      (next) => {
        next.set("page", String(Math.max(1, page - 1)));
      },
      { resetPage: false },
    );
  }

  return (
    <section className="page search-v3-page">
      <header className="search-v3-header">
        <h2>검색 결과</h2>
        <p className="search-v3-subtitle">URL 공유만으로 동일 필터/결과를 재현할 수 있습니다.</p>
        <HeroSearchBox initial={stayInput} onSearch={applySearch} mode="compact" />
      </header>

      <div className="search-v3-layout">
        <aside className="search-filters-panel">
          <section className="filter-section ai-copilot-section">
            <div className="section-headline">
              <h3>AI 추천</h3>
              <button type="button" className="chip-btn" onClick={() => setShowCopilotReasons((prev) => !prev)}>
                {showCopilotReasons ? "근거 닫기" : "근거 보기"}
              </button>
            </div>
            {copilotLoading && <p className="text-muted">추천 생성 중...</p>}
            {copilotError && <p className="error">{copilotError}</p>}
            {copilot && (
              <>
                <p className="copilot-explanation">{copilot.explanation}</p>
                <div className="filter-chip-wrap">
                  {copilot.recommended_filters.map((filter) => (
                    <button
                      key={`${filter.key}:${filter.value}`}
                      type="button"
                      className="chip-btn"
                      onClick={() => applyCopilotFilter(filter)}
                    >
                      {filter.label}
                    </button>
                  ))}
                </div>
                {showCopilotReasons && (
                  <ul className="copilot-reason-list">
                    {copilot.recommended_filters.map((filter) => (
                      <li key={`${filter.key}:${filter.value}:reason`}>
                        <strong>{filter.label}</strong>
                        <span>{filter.reason ?? "도시/결과 분포 기반 추천"}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </section>

          <section className="filter-section">
            <h3>도시 인기 검색 조건</h3>
            <div className="filter-chip-wrap">
              {facets?.popular_filters?.map((preset) => (
                <button
                  key={`${preset.key}:${preset.value}`}
                  type="button"
                  className="chip-btn"
                  onClick={() => applyCopilotFilter({
                    key: preset.key,
                    value: preset.value,
                    label: preset.label,
                  })}
                >
                  {preset.label} ({preset.count})
                </button>
              ))}
            </div>
          </section>

          <section className="filter-section">
            <h3>가격/정렬</h3>
            <label>
              최소 가격
              <input
                type="number"
                value={params.get("min_price") ?? ""}
                onChange={(event) => upsertSingle("min_price", event.target.value)}
              />
            </label>
            <label>
              최대 가격
              <input
                type="number"
                value={params.get("max_price") ?? ""}
                onChange={(event) => upsertSingle("max_price", event.target.value)}
              />
            </label>
            <label>
              최소 평점
              <input
                type="number"
                step="0.1"
                min="0"
                max="5"
                value={params.get("min_rating") ?? ""}
                onChange={(event) => upsertSingle("min_rating", event.target.value)}
              />
            </label>
            <label>
              게스트 평점
              <input
                type="number"
                step="0.1"
                min="0"
                max="10"
                value={params.get("min_guest_rating") ?? ""}
                onChange={(event) => upsertSingle("min_guest_rating", event.target.value)}
              />
            </label>
            <label>
              위치 평점
              <input
                type="number"
                step="0.1"
                min="0"
                max="10"
                value={params.get("min_location_rating") ?? ""}
                onChange={(event) => upsertSingle("min_location_rating", event.target.value)}
              />
            </label>
            <label>
              중심지 거리(m)
              <input
                type="number"
                min="100"
                max="50000"
                value={params.get("max_distance_m") ?? ""}
                onChange={(event) => upsertSingle("max_distance_m", event.target.value)}
              />
            </label>
            <label>
              정렬
              <select
                value={params.get("sort") ?? "best_match"}
                onChange={(event) => upsertSingle("sort", event.target.value)}
              >
                <option value="best_match">Best match</option>
                <option value="price_asc">Price (Low to High)</option>
                <option value="price_desc">Price (High to Low)</option>
                <option value="rating_desc">Rating</option>
                <option value="distance">Distance</option>
              </select>
            </label>
          </section>

          <FacetCheckGroup
            title="숙소 종류"
            paramName="property_type"
            items={facets?.property_types ?? []}
            selected={parseCsvValues(params.get("property_type"))}
            onToggle={toggleMulti}
          />
          <FacetDistrictGroup
            title="지역"
            districts={facets?.districts ?? []}
            selected={parseCsvValues(params.get("districts"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="결제 관련 옵션"
            paramName="payment_options"
            items={facets?.payment_options ?? []}
            selected={parseCsvValues(params.get("payment_options"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="숙소 성급"
            paramName="stars"
            items={facets?.stars ?? []}
            selected={parseCsvValues(params.get("stars"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="이용 가능 서비스/옵션"
            paramName="amenities"
            items={serviceOptionAmenities}
            selected={parseCsvValues(params.get("amenities"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="숙소 편의 시설 및 서비스"
            paramName="amenities"
            items={propertyFacilityAmenities}
            selected={parseCsvValues(params.get("amenities"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="투숙객 평가 점수"
            paramName="guest_rating_bands"
            items={facets?.guest_rating_bands ?? []}
            selected={parseCsvValues(params.get("guest_rating_bands"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="객실 편의 시설/서비스"
            paramName="amenities"
            items={roomFacilityAmenities}
            selected={parseCsvValues(params.get("amenities"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="도심까지의 거리"
            paramName="distance_bands"
            items={facets?.distance_bands ?? []}
            selected={parseCsvValues(params.get("distance_bands"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="침대 종류"
            paramName="bed_types"
            items={facets?.bed_types ?? []}
            selected={parseCsvValues(params.get("bed_types"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="가족 여행객에 인기 옵션"
            paramName="family_options"
            items={facets?.family_options ?? []}
            selected={parseCsvValues(params.get("family_options"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="숙소 위치 평가"
            paramName="location_rating_bands"
            items={facets?.location_rating_bands ?? []}
            selected={parseCsvValues(params.get("location_rating_bands"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="여행 테마"
            paramName="themes"
            items={facets?.themes ?? []}
            selected={parseCsvValues(params.get("themes"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="주변 인기 명소"
            paramName="nearby_attractions"
            items={(facets?.nearby_attractions ?? []).map((poi) => ({
              key: String(poi.poi_id),
              label: poi.name,
              count: poi.count,
            }))}
            selected={parseCsvValues(params.get("nearby_attractions"))}
            onToggle={toggleMulti}
          />
          <FacetSingleSelectGroup
            title="침실 수"
            paramName="bedrooms"
            items={facets?.bedrooms ?? []}
            value={params.get("bedrooms") ?? ""}
            onChange={upsertSingle}
          />
          <FacetCheckGroup
            title="해변 근처"
            paramName="beach_options"
            items={facets?.beach_options ?? []}
            selected={parseCsvValues(params.get("beach_options"))}
            onToggle={toggleMulti}
          />
          <FacetCheckGroup
            title="인기 호텔 브랜드"
            paramName="brands"
            items={facets?.brands ?? []}
            selected={parseCsvValues(params.get("brands"))}
            onToggle={toggleMulti}
          />
        </aside>

        <section className="search-results-panel">
          <div className="search-result-topbar">
            <div className="chips search-chips">
              {activeFilterChips.map((chip) => (
                <button
                  key={`${chip.key}:${chip.value ?? ""}`}
                  type="button"
                  className="chip-btn"
                  onClick={() => removeFilterChip(chip.key, chip.value)}
                >
                  {chip.label} ×
                </button>
              ))}
              {activeFilterChips.length > 0 && (
                <button type="button" className="chip-btn danger" onClick={clearFilters}>
                  필터 전체 초기화
                </button>
              )}
            </div>
            <div className="result-meta">
              {meta ? (
                <p>
                  총 {meta.total.toLocaleString()}개 · {meta.took_ms}ms · 페이지 {meta.page}
                </p>
              ) : (
                <p>결과 정보를 불러오는 중입니다.</p>
              )}
            </div>
          </div>

          {error && <p className="error">{error}</p>}

          {loading ? (
            <ul className="search-skeleton-grid">
              {Array.from({ length: 8 }, (_, index) => (
                <li key={`skeleton-${index}`} className="search-skeleton-card" />
              ))}
            </ul>
          ) : (
            <ul className="card-list search-results-grid">
              {items.map((item) => (
                <li key={item.property_id} className="card search-result-card">
                  <img
                    className="search-thumb"
                    src={item.thumbnail_url || `https://picsum.photos/seed/search-${item.property_id}/420/260`}
                    alt={`${item.name} 썸네일`}
                    loading="lazy"
                  />
                  <div className="search-body">
                    <p className="search-card-subtitle">
                      {item.city ?? "도시 미지정"}
                      {item.district ? ` · ${item.district}` : ""}
                    </p>
                    <h3>{item.name}</h3>
                    <div className="badge-row">
                      <span className="price-badge">최저가 {item.price_min.toLocaleString()} {item.currency ?? locale.currency}</span>
                      <span className="rating-badge">★ {item.rating.toFixed(1)}</span>
                      {typeof item.review_count === "number" && item.review_count > 0 && (
                        <span className="rating-badge">리뷰 {item.review_count.toLocaleString()}</span>
                      )}
                      {typeof item.location_rating === "number" && item.location_rating > 0 && (
                        <span className="rating-badge">위치 {item.location_rating.toFixed(1)}</span>
                      )}
                      {typeof item.star_rating === "number" && item.star_rating > 0 && (
                        <span className="rating-badge">{item.star_rating}성</span>
                      )}
                      {typeof item.distance_m === "number" && item.distance_m > 0 && (
                        <span className="rating-badge">{item.distance_m.toLocaleString()}m</span>
                      )}
                    </div>
                    <div className="search-card-actions">
                      <Link to={`/properties/${item.property_id}`} className="outline-btn">상세 보기</Link>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}

          {!loading && !error && items.length === 0 && (
            <p className="notice warning">조건에 맞는 숙소가 없습니다. 필터를 완화해 주세요.</p>
          )}

          <div className="search-pagination">
            <button type="button" className="chip-btn" disabled={page <= 1} onClick={goPrevPage}>이전</button>
            <span>페이지 {page}</span>
            <button type="button" className="chip-btn" disabled={!hasMore} onClick={goNextPage}>다음</button>
          </div>
        </section>
      </div>
    </section>
  );
}

type FacetCheckGroupProps = {
  title: string;
  paramName: string;
  items: FacetCountItem[];
  selected: string[];
  onToggle: (name: string, value: string, checked: boolean) => void;
};

function FacetCheckGroup({ title, paramName, items, selected, onToggle }: FacetCheckGroupProps) {
  if (!items.length) {
    return null;
  }

  return (
    <section className="filter-section">
      <h3>{title}</h3>
      <ul className="facet-check-list">
        {items.slice(0, 12).map((item) => {
          const checked = selected.includes(item.key);
          return (
            <li key={`${paramName}:${item.key}`}>
              <label>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={(event) => onToggle(paramName, item.key, event.target.checked)}
                />
                <span>{item.label}</span>
                <em>{item.count}</em>
              </label>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

type FacetDistrictGroupProps = {
  title: string;
  districts: Array<{ id: number; name: string; blurb?: string; count: number }>;
  selected: string[];
  onToggle: (name: string, value: string, checked: boolean) => void;
};

function FacetDistrictGroup({ title, districts, selected, onToggle }: FacetDistrictGroupProps) {
  if (!districts.length) {
    return null;
  }
  return (
    <section className="filter-section">
      <h3>{title}</h3>
      <ul className="facet-check-list district-facet-list">
        {districts.slice(0, 14).map((district) => {
          const checked = selected.includes(district.name);
          return (
            <li key={`district:${district.id}`}>
              <label>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={(event) => onToggle("districts", district.name, event.target.checked)}
                />
                <span>{district.name}</span>
                <em>{district.count}</em>
              </label>
              {district.blurb && <small>{district.blurb}</small>}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

type FacetSingleSelectGroupProps = {
  title: string;
  paramName: string;
  items: FacetCountItem[];
  value: string;
  onChange: (name: string, value: string) => void;
};

function FacetSingleSelectGroup({ title, paramName, items, value, onChange }: FacetSingleSelectGroupProps) {
  if (!items.length) {
    return null;
  }
  return (
    <section className="filter-section">
      <h3>{title}</h3>
      <div className="filter-chip-wrap">
        <button
          type="button"
          className={value ? "chip-btn" : "chip-btn active"}
          onClick={() => onChange(paramName, "")}
        >
          전체
        </button>
        {items.slice(0, 8).map((item) => (
          <button
            key={`${paramName}:${item.key}`}
            type="button"
            className={value === item.key ? "chip-btn active" : "chip-btn"}
            onClick={() => onChange(paramName, item.key)}
          >
            {item.label} ({item.count})
          </button>
        ))}
      </div>
    </section>
  );
}

function labelForFilterKey(key: string): string {
  switch (key) {
    case "stars":
      return "성급";
    case "amenities":
      return "편의시설";
    case "property_type":
      return "숙소 종류";
    case "districts":
      return "지역";
    case "payment_options":
      return "결제 옵션";
    case "themes":
      return "여행 테마";
    case "brands":
      return "브랜드";
    case "nearby_attractions":
      return "주변 명소";
    case "bed_types":
      return "침대 종류";
    case "guest_rating_bands":
      return "투숙객 평점";
    case "location_rating_bands":
      return "숙소 위치 평가";
    case "distance_bands":
      return "도심 거리";
    case "family_options":
      return "가족 옵션";
    case "beach_options":
      return "해변 근처";
    default:
      return key;
  }
}

function parseCsvValues(raw: string | null): string[] {
  if (!raw) {
    return [];
  }
  return raw
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

function collectCurrentFilters(params: URLSearchParams): Record<string, string | string[]> {
  const out: Record<string, string | string[]> = {};

  params.forEach((value, key) => {
    if (!value) {
      return;
    }

    if (MULTI_VALUE_FILTERS.has(key)) {
      out[key] = parseCsvValues(value);
    } else if (CLEARABLE_FILTER_KEYS.includes(key)) {
      out[key] = value;
    }
  });

  return out;
}

function addDays(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}
