import { useEffect, useMemo, useRef, useState } from "react";
import { apiGet, apiPost } from "../../api/client";
import { ensureAnonId } from "../../auth/anon";
import { PlaceSuggestion } from "./searchTypes";

type AutocompleteItem = {
  id: string;
  type: string;
  display: string;
  subtitle?: string;
  bucket?: string;
};

type AutocompleteResponse = {
  items: AutocompleteItem[];
};

type AutocompleteFeedbackItemPayload = {
  id: string;
  type: string;
  display: string;
  subtitle?: string;
  position: number;
};

type DestinationRecommendationResponse = {
  city: string;
  country: string;
  districts: Array<{ id: number; name: string; blurb?: string }>;
  pois: Array<{ poi_id: number; name: string; category: string }>;
  featured_properties: Array<{ property_id: number; name: string; stars: number }>;
  country_popular_cities: Array<{ city: string; country: string; property_count: number; highlights: string }>;
};

type Section = {
  key: string;
  title: string;
  rows: PlaceSuggestion[];
};

type RecommendationRows = {
  districtRows: PlaceSuggestion[];
  poiRows: PlaceSuggestion[];
  featuredRows: PlaceSuggestion[];
  countryCityRows: PlaceSuggestion[];
};

type Props = {
  value: string;
  placeId?: string;
  cityHint?: string;
  placeholder?: string;
  className?: string;
  onChange: (value: string) => void;
  onSelect: (item: PlaceSuggestion) => void;
  onSubmitFreeText?: (value: string) => void;
};

const AUTOCOMPLETE_TYPES = "city,property,poi,station,airport";
const AUTOCOMPLETE_TYPE_LIST = AUTOCOMPLETE_TYPES.split(",");
const FEEDBACK_SUPPORTED_TYPES = new Set(["CITY", "PROPERTY", "POI", "STATION", "AIRPORT"]);
const RECENT_STORAGE_KEY = "stayvista.search.recent_places";

const TYPE_LABELS: Record<string, string> = {
  CITY: "인기 도시",
  DISTRICT: "추천 지역",
  PROPERTY: "숙소",
  POI: "명소",
  STATION: "역",
  AIRPORT: "공항",
};

export function UnifiedAutocomplete({
  value,
  placeId,
  cityHint,
  placeholder = "도시/숙소/명소 입력",
  className,
  onChange,
  onSelect,
  onSubmitFreeText,
}: Props) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sections, setSections] = useState<Section[]>([]);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [isComposing, setIsComposing] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const anonIdRef = useRef<string>(ensureAnonId());

  const flatRows = useMemo(() => sections.flatMap((section) => section.rows), [sections]);
  const rowIndexMap = useMemo(() => {
    const map = new Map<string, number>();
    flatRows.forEach((row, index) => {
      map.set(rowIdentity(row), index);
    });
    return map;
  }, [flatRows]);

  useEffect(() => {
    function onOutsideClick(event: MouseEvent) {
      const target = event.target as Node | null;
      if (target && wrapRef.current?.contains(target)) {
        return;
      }
      setOpen(false);
      setActiveIndex(-1);
    }

    document.addEventListener("mousedown", onOutsideClick);
    return () => document.removeEventListener("mousedown", onOutsideClick);
  }, []);

  useEffect(() => {
    if (!open || isComposing) {
      return;
    }

    const input = value.normalize("NFC").trim();
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setLoading(true);
      setError(null);
      try {
        const query = new URLSearchParams();
        if (input) {
          query.set("q", input);
        }
        query.set("types", AUTOCOMPLETE_TYPES);
        query.set("size", "12");
        query.set("lang", "ko");

        const [autocompleteRes, recommendationRes] = await Promise.all([
          apiGet<AutocompleteResponse>(
            `/v1/autocomplete?${query.toString()}`,
            { "X-Anon-Id": anonIdRef.current },
            controller.signal,
          ).then((res) => res.data),
          input
            ? Promise.resolve<DestinationRecommendationResponse | null>(null)
            : apiGet<DestinationRecommendationResponse>(
                `/v1/destinations/recommendations?city_id=${encodeURIComponent(resolveCity(cityHint, placeId))}&lang=ko&limit=8`,
                {},
                controller.signal,
              )
                .then((res) => res.data)
                .catch(() => null),
        ]);

        const autocompleteRows = autocompleteRes.items.map((item) => autocompleteItemToRow(item));
        const recommendationRows = recommendationRes
          ? recommendationToRows(recommendationRes)
          : { districtRows: [], poiRows: [], featuredRows: [], countryCityRows: [] };
        const nextSections = input
          ? buildTypedSections(autocompleteRows)
          : buildEmptySections(autocompleteRows, recommendationRows, loadLocalRecent(), recommendationRes?.country);
        const nextFlatRows = nextSections.flatMap((section) => section.rows);

        setSections(nextSections);
        setActiveIndex(nextFlatRows.length > 0 ? 0 : -1);
        void sendAutocompleteImpression({
          anonId: anonIdRef.current,
          q: input,
          rows: nextFlatRows,
        });
      } catch (e) {
        if ((e as Error).name === "AbortError") {
          return;
        }
        setError("자동완성 결과를 불러오지 못했습니다.");
        setSections([]);
      } finally {
        setLoading(false);
      }
    }, 180);

    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [cityHint, isComposing, open, placeId, value]);

  function submitFreeText() {
    const normalized = value.normalize("NFC").trim();
    onSubmitFreeText?.(normalized);
    setOpen(false);
    setActiveIndex(-1);
  }

  function selectRow(row: PlaceSuggestion) {
    void sendAutocompleteSelect({
      anonId: anonIdRef.current,
      q: value.normalize("NFC").trim(),
      rows: flatRows,
      selectedRow: row,
      selectedIndex: rowIndexMap.get(rowIdentity(row)) ?? -1,
    });
    saveLocalRecent(row);
    onSelect(row);
    setOpen(false);
    setActiveIndex(-1);
  }

  return (
    <div className={className ?? "unified-autocomplete"} ref={wrapRef}>
      <input
        value={value}
        placeholder={placeholder}
        onFocus={() => setOpen(true)}
        onChange={(event) => {
          const next = event.target.value;
          if (isComposing) {
            onChange(next);
          } else {
            onChange(next.normalize("NFC"));
          }
          setOpen(true);
        }}
        onCompositionStart={() => setIsComposing(true)}
        onCompositionEnd={(event) => {
          setIsComposing(false);
          onChange(event.currentTarget.value.normalize("NFC"));
          setOpen(true);
        }}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" && !open) {
            setOpen(true);
            event.preventDefault();
            return;
          }

          if (!open) {
            if (event.key === "Enter") {
              event.preventDefault();
              submitFreeText();
            }
            return;
          }

          if (event.key === "ArrowDown") {
            event.preventDefault();
            if (!flatRows.length) return;
            setActiveIndex((prev) => (prev + 1) % flatRows.length);
            return;
          }

          if (event.key === "ArrowUp") {
            event.preventDefault();
            if (!flatRows.length) return;
            setActiveIndex((prev) => (prev <= 0 ? flatRows.length - 1 : prev - 1));
            return;
          }

          if (event.key === "Enter") {
            event.preventDefault();
            if (activeIndex >= 0 && activeIndex < flatRows.length) {
              selectRow(flatRows[activeIndex]);
            } else {
              submitFreeText();
            }
            return;
          }

          if (event.key === "Escape") {
            setOpen(false);
            setActiveIndex(-1);
          }
        }}
      />
      {open && (
        <div className="autocomplete-dropdown" role="listbox" aria-label="통합 자동완성 목록">
          {loading && <p className="autocomplete-status">추천어를 불러오는 중...</p>}
          {!loading && error && <p className="autocomplete-status error">{error}</p>}
          {!loading && !error && !sections.length && (
            <p className="autocomplete-status">추천어가 없습니다.</p>
          )}
          {!loading && !error && sections.map((section) => (
            <div key={section.key} className="autocomplete-section">
              <p className="autocomplete-section-title">{section.title}</p>
              <ul>
                {section.rows.map((row) => {
                  const index = rowIndexMap.get(rowIdentity(row)) ?? -1;
                  return (
                    <li key={rowIdentity(row)}>
                      <button
                        type="button"
                        className={index === activeIndex ? "autocomplete-row active" : "autocomplete-row"}
                        onMouseEnter={() => setActiveIndex(index)}
                        onMouseDown={(event) => event.preventDefault()}
                        onClick={() => selectRow(row)}
                      >
                        <span className="autocomplete-row-main">{row.display}</span>
                        {row.subtitle && <span className="autocomplete-row-sub">{row.subtitle}</span>}
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function autocompleteItemToRow(item: AutocompleteItem): PlaceSuggestion {
  return {
    id: item.id,
    placeId: item.id,
    type: item.type,
    display: item.display,
    subtitle: item.subtitle,
    bucket: item.bucket,
    city: inferCity(item.id, item.subtitle),
  };
}

async function sendAutocompleteImpression({
  anonId,
  q,
  rows,
}: {
  anonId: string;
  q: string;
  rows: PlaceSuggestion[];
}) {
  const items = rowsToFeedbackItems(rows);
  if (!items.length) {
    return;
  }

  await apiPost(
    "/v1/autocomplete/feedback/impression",
    {
      anon_id: anonId,
      q: q || null,
      lang: "ko",
      types: AUTOCOMPLETE_TYPE_LIST,
      size: items.length,
      items,
    },
    { "X-Anon-Id": anonId },
  ).catch(() => undefined);
}

async function sendAutocompleteSelect({
  anonId,
  q,
  rows,
  selectedRow,
  selectedIndex,
}: {
  anonId: string;
  q: string;
  rows: PlaceSuggestion[];
  selectedRow: PlaceSuggestion;
  selectedIndex: number;
}) {
  const selected = rowToFeedbackItem(selectedRow, selectedIndex);
  if (!selected) {
    return;
  }

  const items = rowsToFeedbackItems(rows);
  await apiPost(
    "/v1/autocomplete/feedback/select",
    {
      anon_id: anonId,
      q: q || null,
      lang: "ko",
      types: AUTOCOMPLETE_TYPE_LIST,
      size: items.length,
      items,
      selected,
    },
    { "X-Anon-Id": anonId },
  ).catch(() => undefined);
}

function recommendationToRows(data: DestinationRecommendationResponse): RecommendationRows {
  const districtRows: PlaceSuggestion[] = data.districts.map((district) => ({
    id: `district:${data.city}:${district.id}`,
    placeId: `city:${data.city}`,
    type: "DISTRICT",
    display: district.name,
    subtitle: district.blurb || `${data.city} 추천 지역`,
    bucket: "recommended",
    city: data.city,
    district: district.name,
  }));

  const poiRows: PlaceSuggestion[] = data.pois.map((poi) => ({
    id: `poi:${poi.poi_id}`,
    placeId: `poi:${poi.poi_id}`,
    type: "POI",
    display: poi.name,
    subtitle: `${data.city} · ${poi.category}`,
    bucket: "recommended",
    city: data.city,
  }));

  const featuredRows: PlaceSuggestion[] = data.featured_properties.map((property) => ({
    id: `property:${property.property_id}`,
    placeId: `property:${property.property_id}`,
    type: "PROPERTY",
    display: property.name,
    subtitle: `${data.city} · ${property.stars}성`,
    bucket: "recommended",
    city: data.city,
  }));

  const countryCityRows: PlaceSuggestion[] = data.country_popular_cities.map((city) => ({
    id: `country-city:${city.country}:${city.city}`,
    placeId: `city:${city.city}`,
    type: "CITY",
    display: city.city,
    subtitle: city.highlights || `${city.country} 인기 도시`,
    bucket: "country_popular",
    city: city.city,
  }));

  return {
    districtRows: dedupeRows(districtRows),
    poiRows: dedupeRows(poiRows),
    featuredRows: dedupeRows(featuredRows),
    countryCityRows: dedupeRows(countryCityRows),
  };
}

function buildTypedSections(rows: PlaceSuggestion[]): Section[] {
  const grouped = new Map<string, PlaceSuggestion[]>();
  rows.forEach((row) => {
    const key = row.type.toUpperCase();
    const current = grouped.get(key) ?? [];
    current.push(row);
    grouped.set(key, current);
  });

  const sectionOrder = ["CITY", "PROPERTY", "POI", "STATION", "AIRPORT"];
  return sectionOrder
    .filter((key) => grouped.has(key))
    .map((key) => ({
      key,
      title: TYPE_LABELS[key] ?? key,
      rows: dedupeRows(grouped.get(key) ?? []).slice(0, 8),
    }));
}

function buildEmptySections(
  autocompleteRows: PlaceSuggestion[],
  recommendationRows: RecommendationRows,
  localRecentRows: PlaceSuggestion[],
  countryCode?: string,
): Section[] {
  const recentRows = dedupeRows([
    ...localRecentRows,
    ...autocompleteRows.filter((row) => row.bucket === "recent"),
  ]).slice(0, 6);

  const recommendedDistrictRows = recommendationRows.districtRows.slice(0, 6);
  const poiRows = recommendationRows.poiRows.slice(0, 6);
  const featuredRows = recommendationRows.featuredRows.slice(0, 6);
  const countryPopularCityRows = recommendationRows.countryCityRows.slice(0, 8);
  const fallbackPopularCities = dedupeRows(
    autocompleteRows.filter((row) => row.type === "CITY" && row.bucket !== "recent" && row.bucket !== "country_popular"),
  ).slice(0, 6);
  const popularCityRows = countryPopularCityRows.length > 0 ? countryPopularCityRows : fallbackPopularCities;

  const sections: Section[] = [];
  if (recentRows.length) {
    sections.push({ key: "recent", title: "최근 검색", rows: recentRows });
  }
  if (recommendedDistrictRows.length) {
    sections.push({ key: "district", title: "추천 지역", rows: recommendedDistrictRows });
  }
  if (poiRows.length) {
    sections.push({ key: "poi", title: "명소", rows: poiRows });
  }
  if (featuredRows.length) {
    sections.push({ key: "featured", title: "숙소", rows: featuredRows });
  }
  if (popularCityRows.length) {
    sections.push({ key: "city", title: `${countryLabel(countryCode)} 인기 도시`, rows: popularCityRows });
  }
  return sections;
}

function rowIdentity(row: PlaceSuggestion): string {
  return `${row.id}|${row.type}|${row.placeId ?? ""}`;
}

function rowsToFeedbackItems(rows: PlaceSuggestion[]): AutocompleteFeedbackItemPayload[] {
  return rows
    .map((row, index) => rowToFeedbackItem(row, index))
    .filter((item): item is AutocompleteFeedbackItemPayload => item !== null);
}

function rowToFeedbackItem(row: PlaceSuggestion, position: number): AutocompleteFeedbackItemPayload | null {
  const normalizedType = row.type.trim().toUpperCase();
  if (!FEEDBACK_SUPPORTED_TYPES.has(normalizedType)) {
    return null;
  }

  const id = (row.placeId ?? row.id)?.trim();
  if (!id) {
    return null;
  }

  const splitIndex = id.indexOf(":");
  if (splitIndex <= 0 || splitIndex === id.length - 1) {
    return null;
  }

  const encodedType = id.slice(0, splitIndex).trim().toUpperCase();
  if (!FEEDBACK_SUPPORTED_TYPES.has(encodedType)) {
    return null;
  }

  return {
    id,
    type: normalizedType,
    display: row.display,
    subtitle: row.subtitle,
    position,
  };
}

function dedupeRows(rows: PlaceSuggestion[]): PlaceSuggestion[] {
  const seen = new Set<string>();
  return rows.filter((row) => {
    const key = rowIdentity(row);
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function resolveCity(cityHint?: string, placeId?: string): string {
  if (cityHint && cityHint.trim()) {
    return cityHint.trim();
  }
  if (placeId?.startsWith("city:")) {
    return placeId.slice("city:".length);
  }
  return "Seoul";
}

function inferCity(placeId?: string, subtitle?: string): string | undefined {
  if (placeId?.startsWith("city:")) {
    return placeId.slice("city:".length);
  }
  if (!subtitle) {
    return undefined;
  }
  const city = subtitle.split("·")[0]?.trim();
  return city || undefined;
}

function countryLabel(country?: string): string {
  switch ((country ?? "").toUpperCase()) {
    case "KR":
      return "대한민국";
    case "JP":
      return "일본";
    case "US":
      return "미국";
    case "GB":
      return "영국";
    case "FR":
      return "프랑스";
    default:
      return "해당 국가";
  }
}

function loadLocalRecent(): PlaceSuggestion[] {
  if (typeof window === "undefined") {
    return [];
  }
  const raw = window.localStorage.getItem(RECENT_STORAGE_KEY);
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw) as PlaceSuggestion[];
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .filter((row) => row && typeof row.id === "string" && typeof row.type === "string" && typeof row.display === "string")
      .slice(0, 10);
  } catch {
    return [];
  }
}

function saveLocalRecent(row: PlaceSuggestion) {
  if (typeof window === "undefined") {
    return;
  }
  const current = loadLocalRecent();
  const next = dedupeRows([row, ...current]).slice(0, 10);
  window.localStorage.setItem(RECENT_STORAGE_KEY, JSON.stringify(next));
}
