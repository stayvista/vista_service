import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import maplibregl, { GeoJSONSource, Map as MapLibreMap, type StyleSpecification } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { apiGet } from "../api/client";

type NearbySort = "distance" | "popularity" | "rating";
type MobileTab = "map" | "list";

type NearbyItem = {
  id: number;
  name: string;
  category?: string;
  lat: number;
  lng: number;
  distance_m: number;
  rating_score?: number;
  review_count?: number;
  preview?: {
    thumbnail_url?: string;
    address?: string;
    snippet?: string;
  };
};

type NearbyMeta = {
  bbox: string;
  returned: number;
  has_more: boolean;
  offset: number;
  limit: number;
};

type NearbyData = {
  items: NearbyItem[];
  meta: NearbyMeta;
};

type PoiDetail = {
  id: number;
  name: string;
  category?: string;
  lat: number;
  lng: number;
  address?: string;
  description?: string;
  images: string[];
  links: {
    naver?: string;
    google?: string;
    osm?: string;
  };
  related: {
    properties: Array<{
      property_id: number;
      name: string;
      city?: string;
      rating: number;
      thumbnail_url?: string;
    }>;
    products: Array<{
      product_id: number;
      name: string;
      category: string;
      city?: string;
    }>;
  };
};

type ApiError = { code?: string; message?: string };

type NearbyFeatureCollection = {
  type: "FeatureCollection";
  features: Array<{
    type: "Feature";
    geometry: { type: "Point"; coordinates: [number, number] };
    properties: { id: number; name: string; category: string };
  }>;
};

const DEFAULT_LAT = 37.501;
const DEFAULT_LNG = 127.0396;
const DEFAULT_ZOOM = 13;
const DEFAULT_RADIUS = 2500;
const DEFAULT_LIMIT = 120;
const MOBILE_BREAKPOINT = 760;
const MIN_SIDEBAR_WIDTH = 320;
const MAX_SIDEBAR_WIDTH = 640;
const DEFAULT_SIDEBAR_WIDTH = 420;
const MIN_LIST_PANE_PERCENT = 35;
const MAX_LIST_PANE_PERCENT = 78;
const DEFAULT_LIST_PANE_PERCENT = 56;

const CATEGORY_OPTIONS = [
  { value: "", label: "전체" },
  { value: "attraction", label: "관광" },
  { value: "food", label: "맛집" },
  { value: "shopping", label: "쇼핑" },
  { value: "museum", label: "전시" },
];

const SORT_OPTIONS: Array<{ value: NearbySort; label: string }> = [
  { value: "distance", label: "거리순" },
  { value: "popularity", label: "인기순" },
  { value: "rating", label: "평점순" },
];

const CATEGORY_LABELS: Record<string, string> = {
  attraction: "관광",
  food: "음식점",
  shopping: "쇼핑",
  museum: "전시",
};

const CLUSTER_LAYER_ID = "nearby-clusters";
const CLUSTER_COUNT_LAYER_ID = "nearby-cluster-count";
const POINT_LAYER_ID = "nearby-points";
const HOVER_LAYER_ID = "nearby-points-hover";
const SELECTED_LAYER_ID = "nearby-points-selected";
const SOURCE_ID = "nearby-poi";

const OSM_RASTER_STYLE: StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: "raster",
      tiles: [
        "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
        "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
        "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
      ],
      tileSize: 256,
      attribution: "&copy; OpenStreetMap contributors",
    },
  },
  layers: [
    {
      id: "osm",
      type: "raster",
      source: "osm",
      minzoom: 0,
      maxzoom: 19,
    },
  ],
};

function parseNumber(raw: string | null, fallback: number): number {
  const value = Number(raw);
  return Number.isFinite(value) ? value : fallback;
}

function parseSort(raw: string | null): NearbySort {
  if (raw === "distance" || raw === "popularity" || raw === "rating") {
    return raw;
  }
  return "distance";
}

function categoryLabel(category?: string): string {
  if (!category) return "기타";
  return CATEGORY_LABELS[category] ?? category.toUpperCase();
}

function buildFallbackImage(id: number): string {
  return `https://picsum.photos/seed/stayvista-poi-${id}/640/420`;
}

function reviewSummary(rating?: number, reviewCount?: number): string {
  if (rating && reviewCount) {
    return `★ ${rating.toFixed(1)} · 리뷰 ${reviewCount.toLocaleString()}`;
  }
  if (rating) {
    return `★ ${rating.toFixed(1)} · 리뷰 정보 준비중`;
  }
  if (reviewCount) {
    return `리뷰 ${reviewCount.toLocaleString()}`;
  }
  return "리뷰 정보 준비중";
}

function toFeatureCollection(items: NearbyItem[]): NearbyFeatureCollection {
  return {
    type: "FeatureCollection",
    features: items.map((item) => ({
      type: "Feature",
      geometry: {
        type: "Point",
        coordinates: [item.lng, item.lat],
      },
      properties: {
        id: item.id,
        name: item.name,
        category: item.category ?? "etc",
      },
    })),
  };
}

function normalizeBounds(map: MapLibreMap): string {
  const bounds = map.getBounds();
  const sw = bounds.getSouthWest();
  const ne = bounds.getNorthEast();
  return `${sw.lat.toFixed(6)},${sw.lng.toFixed(6)},${ne.lat.toFixed(6)},${ne.lng.toFixed(6)}`;
}

function shouldApplyRadiusFilter(map: MapLibreMap): boolean {
  const bounds = map.getBounds();
  const latSpan = Math.abs(bounds.getNorth() - bounds.getSouth());
  const lngSpan = Math.abs(bounds.getEast() - bounds.getWest());
  // Wide viewport search should use bbox-first semantics; radius becomes too restrictive.
  return latSpan <= 1.0 && lngSpan <= 1.0;
}

function formatError(error: unknown, fallback: string): string {
  const apiError = error as ApiError;
  if (apiError?.code || apiError?.message) {
    return `${apiError.code ?? "ERROR"}: ${apiError.message ?? fallback}`;
  }
  if (error instanceof Error) {
    return `${fallback}: ${error.message}`;
  }
  return fallback;
}

function ensureMapLayers(map: MapLibreMap) {
  if (map.getSource(SOURCE_ID)) {
    return;
  }

  map.addSource(SOURCE_ID, {
    type: "geojson",
    data: toFeatureCollection([]) as unknown as GeoJSON.FeatureCollection,
    cluster: true,
    clusterRadius: 52,
    clusterMaxZoom: 14,
  });

  map.addLayer({
    id: CLUSTER_LAYER_ID,
    type: "circle",
    source: SOURCE_ID,
    filter: ["has", "point_count"],
    paint: {
      "circle-color": [
        "step",
        ["get", "point_count"],
        "#1457b8",
        20,
        "#0f7b6c",
        60,
        "#fb8b24",
      ],
      "circle-radius": [
        "step",
        ["get", "point_count"],
        20,
        20,
        26,
        60,
        32,
      ],
      "circle-opacity": 0.86,
      "circle-stroke-color": "#ffffff",
      "circle-stroke-width": 2,
    },
  });

  map.addLayer({
    id: CLUSTER_COUNT_LAYER_ID,
    type: "symbol",
    source: SOURCE_ID,
    filter: ["has", "point_count"],
    layout: {
      "text-field": ["get", "point_count_abbreviated"],
      "text-font": ["Open Sans Bold"],
      "text-size": 12,
    },
    paint: {
      "text-color": "#ffffff",
    },
  });

  map.addLayer({
    id: POINT_LAYER_ID,
    type: "circle",
    source: SOURCE_ID,
    filter: ["!", ["has", "point_count"]],
    paint: {
      "circle-color": [
        "match",
        ["get", "category"],
        "food",
        "#de3c4b",
        "shopping",
        "#cc7a00",
        "museum",
        "#7a5cff",
        "attraction",
        "#0f7b6c",
        "#1457b8",
      ],
      "circle-radius": 8,
      "circle-stroke-color": "#ffffff",
      "circle-stroke-width": 2,
    },
  });

  map.addLayer({
    id: HOVER_LAYER_ID,
    type: "circle",
    source: SOURCE_ID,
    filter: ["==", ["get", "id"], -1],
    paint: {
      "circle-radius": 14,
      "circle-color": "#00a2ff",
      "circle-opacity": 0.25,
    },
  });

  map.addLayer({
    id: SELECTED_LAYER_ID,
    type: "circle",
    source: SOURCE_ID,
    filter: ["==", ["get", "id"], -1],
    paint: {
      "circle-radius": 16,
      "circle-color": "#0f5eff",
      "circle-opacity": 0.18,
      "circle-stroke-color": "#0f5eff",
      "circle-stroke-width": 2,
    },
  });
}

export function NearbyPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const [category, setCategory] = useState(searchParams.get("category") ?? "");
  const [sort, setSort] = useState<NearbySort>(parseSort(searchParams.get("sort")));
  const [radius, setRadius] = useState(
    Math.max(500, Math.min(10000, parseNumber(searchParams.get("radius"), DEFAULT_RADIUS)))
  );
  const [autoSearch, setAutoSearch] = useState(searchParams.get("auto") === "1");
  const [mobileTab, setMobileTab] = useState<MobileTab>("map");
  const [isCompactLayout, setIsCompactLayout] = useState(() => window.innerWidth <= MOBILE_BREAKPOINT);
  const [sidebarWidth, setSidebarWidth] = useState(DEFAULT_SIDEBAR_WIDTH);
  const [listPanePercent, setListPanePercent] = useState(DEFAULT_LIST_PANE_PERCENT);

  const [centerLat, setCenterLat] = useState(parseNumber(searchParams.get("lat"), DEFAULT_LAT));
  const [centerLng, setCenterLng] = useState(parseNumber(searchParams.get("lng"), DEFAULT_LNG));
  const [zoom, setZoom] = useState(parseNumber(searchParams.get("zoom"), DEFAULT_ZOOM));

  const [items, setItems] = useState<NearbyItem[]>([]);
  const [meta, setMeta] = useState<NearbyMeta | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [viewportDirty, setViewportDirty] = useState(false);
  const [mapReady, setMapReady] = useState(false);
  const [locating, setLocating] = useState(false);
  const [radiusEnabledForViewport, setRadiusEnabledForViewport] = useState(true);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [hoveredId, setHoveredId] = useState<number | null>(null);
  const [panelOpen, setPanelOpen] = useState(false);
  const [selectedDetail, setSelectedDetail] = useState<PoiDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [imageIndex, setImageIndex] = useState(0);

  const [savedIds, setSavedIds] = useState<Set<number>>(() => {
    try {
      const raw = localStorage.getItem("stayvista_nearby_saved");
      if (!raw) return new Set<number>();
      const parsed = JSON.parse(raw) as number[];
      return new Set(parsed);
    } catch {
      return new Set<number>();
    }
  });

  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const mapReadyRef = useRef(false);
  const searchDebounceRef = useRef<number | null>(null);
  const mapResizeFrameRef = useRef<number | null>(null);
  const nearbyAbortRef = useRef<AbortController | null>(null);
  const inflightNearbyRef = useRef<Map<string, Promise<NearbyData>>>(new Map());
  const detailCacheRef = useRef<Map<number, PoiDetail>>(new Map());
  const leftPanelRef = useRef<HTMLDivElement | null>(null);
  const selectedIdRef = useRef<number | null>(null);

  const selectedItem = useMemo(
    () => items.find((item) => item.id === selectedId) ?? null,
    [items, selectedId]
  );
  const showDesktopDetail = selectedId != null && !isCompactLayout;

  useEffect(() => {
    selectedIdRef.current = selectedId;
  }, [selectedId]);

  const effectiveImages = useMemo(() => {
    if (selectedDetail?.images?.length) return selectedDetail.images;
    if (selectedItem?.preview?.thumbnail_url) return [selectedItem.preview.thumbnail_url];
    if (selectedItem) return [buildFallbackImage(selectedItem.id)];
    return [];
  }, [selectedDetail, selectedItem]);

  useEffect(() => {
    const onResize = () => {
      setIsCompactLayout(window.innerWidth <= MOBILE_BREAKPOINT);
    };
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
    };
  }, []);

  useEffect(() => {
    const next = new URLSearchParams();
    if (category) next.set("category", category);
    next.set("sort", sort);
    next.set("radius", String(radius));
    next.set("lat", centerLat.toFixed(5));
    next.set("lng", centerLng.toFixed(5));
    next.set("zoom", zoom.toFixed(2));
    if (autoSearch) next.set("auto", "1");
    setSearchParams(next, { replace: true });
  }, [autoSearch, category, centerLat, centerLng, radius, setSearchParams, sort, zoom]);

  useEffect(() => {
    try {
      localStorage.setItem("stayvista_nearby_saved", JSON.stringify(Array.from(savedIds)));
    } catch {
      // ignore
    }
  }, [savedIds]);

  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) {
      return;
    }

    const map = new maplibregl.Map({
      container: mapContainerRef.current,
      style: OSM_RASTER_STYLE,
      center: [centerLng, centerLat],
      zoom,
      minZoom: 4,
      maxZoom: 18,
    });

    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-right");

    map.on("load", () => {
      ensureMapLayers(map);
      mapReadyRef.current = true;
      setMapReady(true);
      setViewportDirty(true);
    });

    map.on("moveend", () => {
      if (!mapReadyRef.current) return;
      const center = map.getCenter();
      setCenterLat(center.lat);
      setCenterLng(center.lng);
      setZoom(map.getZoom());
      setViewportDirty(true);
    });

    map.on("click", CLUSTER_LAYER_ID, (event) => {
      const feature = event.features?.[0];
      if (!feature || feature.geometry.type !== "Point") return;

      const source = map.getSource(SOURCE_ID) as GeoJSONSource;
      const clusterId = Number(feature.properties?.cluster_id);
      void source.getClusterExpansionZoom(clusterId)
        .then((expansionZoom) => {
          map.easeTo({
            center: (feature.geometry as GeoJSON.Point).coordinates as [number, number],
            zoom: expansionZoom,
            duration: 420,
          });
        })
        .catch(() => {
          // no-op
        });
    });

    map.on("click", POINT_LAYER_ID, (event) => {
      const feature = event.features?.[0];
      const id = Number(feature?.properties?.id);
      if (!Number.isFinite(id)) return;

      setSelectedId(id);
      setPanelOpen(true);
      setMobileTab("map");
    });

    map.on("mousemove", POINT_LAYER_ID, (event) => {
      const feature = event.features?.[0];
      const id = Number(feature?.properties?.id);
      if (!Number.isFinite(id)) {
        setHoveredId(null);
        return;
      }
      setHoveredId(id);
    });

    map.on("mouseleave", POINT_LAYER_ID, () => {
      setHoveredId((previous) => (previous === selectedIdRef.current ? previous : null));
    });

    map.on("mouseenter", POINT_LAYER_ID, () => {
      map.getCanvas().style.cursor = "pointer";
    });

    map.on("mouseleave", POINT_LAYER_ID, () => {
      map.getCanvas().style.cursor = "";
    });

    mapRef.current = map;

    return () => {
      nearbyAbortRef.current?.abort();
      if (searchDebounceRef.current) {
        window.clearTimeout(searchDebounceRef.current);
      }
      if (mapResizeFrameRef.current != null) {
        window.cancelAnimationFrame(mapResizeFrameRef.current);
      }
      map.remove();
      mapRef.current = null;
      mapReadyRef.current = false;
      setMapReady(false);
    };
  }, []);

  useEffect(() => {
    if (!mapRef.current || !mapReadyRef.current) {
      return;
    }
    const source = mapRef.current.getSource(SOURCE_ID) as GeoJSONSource | undefined;
    if (!source) {
      return;
    }
    source.setData(toFeatureCollection(items) as unknown as GeoJSON.FeatureCollection);
  }, [items]);

  useEffect(() => {
    if (!mapRef.current || !mapReadyRef.current) {
      return;
    }

    mapRef.current.setFilter(HOVER_LAYER_ID, ["==", ["get", "id"], hoveredId ?? -1]);
    mapRef.current.setFilter(SELECTED_LAYER_ID, ["==", ["get", "id"], selectedId ?? -1]);
  }, [hoveredId, selectedId]);

  const fetchNearbyData = useCallback(
    (requestKey: string, path: string, signal?: AbortSignal): Promise<NearbyData> => {
      const cached = inflightNearbyRef.current.get(requestKey);
      if (cached) {
        return cached;
      }

      const request = apiGet<NearbyData>(path, {}, signal)
        .then((response) => response.data)
        .finally(() => {
          inflightNearbyRef.current.delete(requestKey);
        });

      inflightNearbyRef.current.set(requestKey, request);
      return request;
    },
    []
  );

  const scheduleMapResize = useCallback(() => {
    if (!mapRef.current || !mapReadyRef.current) {
      return;
    }
    if (mapResizeFrameRef.current != null) {
      window.cancelAnimationFrame(mapResizeFrameRef.current);
    }
    mapResizeFrameRef.current = window.requestAnimationFrame(() => {
      mapRef.current?.resize();
      mapResizeFrameRef.current = null;
    });
  }, []);

  const runNearbySearch = useCallback(async () => {
    const map = mapRef.current;
    if (!map || !mapReadyRef.current) {
      return;
    }

    const bbox = normalizeBounds(map);
    const center = `${map.getCenter().lat.toFixed(6)},${map.getCenter().lng.toFixed(6)}`;
    const applyRadius = shouldApplyRadiusFilter(map);
    const query = new URLSearchParams({
      bbox,
      sort,
      limit: String(DEFAULT_LIMIT),
      center,
    });
    if (applyRadius) {
      query.set("radius_m", String(radius));
    }
    if (category) {
      query.set("category", category);
    }

    setRadiusEnabledForViewport(applyRadius);

    const radiusKey = applyRadius ? String(radius) : "none";
    const requestKey = `${bbox}|${category}|${sort}|${radiusKey}`;
    let signal: AbortSignal | undefined;
    if (!inflightNearbyRef.current.has(requestKey)) {
      nearbyAbortRef.current?.abort();
      nearbyAbortRef.current = new AbortController();
      signal = nearbyAbortRef.current.signal;
    }

    setLoading(true);
    setError(null);

    try {
      const data = await fetchNearbyData(requestKey, `/v1/poi/nearby?${query.toString()}`, signal);
      setItems(data.items);
      setMeta(data.meta);
      setViewportDirty(false);
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        return;
      }
      setError(formatError(error, "지도를 기준으로 주변 추천을 불러오지 못했습니다."));
    } finally {
      setLoading(false);
    }
  }, [category, fetchNearbyData, radius, sort]);

  useEffect(() => {
    if (!mapReadyRef.current || !viewportDirty) {
      return;
    }
    if (!autoSearch) {
      return;
    }

    if (searchDebounceRef.current) {
      window.clearTimeout(searchDebounceRef.current);
    }

    searchDebounceRef.current = window.setTimeout(() => {
      void runNearbySearch();
    }, 400);

    return () => {
      if (searchDebounceRef.current) {
        window.clearTimeout(searchDebounceRef.current);
      }
    };
  }, [autoSearch, runNearbySearch, viewportDirty]);

  useEffect(() => {
    scheduleMapResize();
  }, [isCompactLayout, listPanePercent, mobileTab, scheduleMapResize, showDesktopDetail, sidebarWidth]);

  useEffect(() => {
    if (!mapReady) {
      return;
    }
    void runNearbySearch();
  }, [mapReady, runNearbySearch]);

  useEffect(() => {
    if (selectedId == null) {
      setPanelOpen(false);
      setSelectedDetail(null);
      setDetailError(null);
      setDetailLoading(false);
      return;
    }

    setPanelOpen(true);
    setDetailError(null);
    setImageIndex(0);

    const cachedDetail = detailCacheRef.current.get(selectedId);
    if (cachedDetail) {
      setSelectedDetail(cachedDetail);
      return;
    }

    setDetailLoading(true);
    void apiGet<PoiDetail>(`/v1/poi/${selectedId}`)
      .then((response) => {
        detailCacheRef.current.set(selectedId, response.data);
        setSelectedDetail(response.data);
      })
      .catch((error: unknown) => {
        setDetailError(formatError(error, "상세 정보를 불러오지 못했습니다."));
      })
      .finally(() => {
        setDetailLoading(false);
      });
  }, [selectedId]);

  useEffect(() => {
    if (selectedId == null) {
      return;
    }
    const exists = items.some((item) => item.id === selectedId);
    if (!exists) {
      setSelectedId(null);
      setPanelOpen(false);
    }
  }, [items, selectedId]);

  function retryDetail() {
    if (selectedId == null) return;
    detailCacheRef.current.delete(selectedId);
    setSelectedDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    void apiGet<PoiDetail>(`/v1/poi/${selectedId}`)
      .then((response) => {
        detailCacheRef.current.set(selectedId, response.data);
        setSelectedDetail(response.data);
      })
      .catch((error: unknown) => {
        setDetailError(formatError(error, "상세 정보를 다시 불러오지 못했습니다."));
      })
      .finally(() => {
        setDetailLoading(false);
      });
  }

  function closePanel() {
    setPanelOpen(false);
    setSelectedId(null);
    setHoveredId(null);
  }

  function useCurrentLocation() {
    if (!navigator.geolocation || !mapRef.current) {
      setError("현재 브라우저에서 위치 정보를 사용할 수 없습니다.");
      return;
    }
    if (locating) {
      return;
    }

    setLocating(true);
    setError(null);

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const lat = Number(position.coords.latitude.toFixed(6));
        const lng = Number(position.coords.longitude.toFixed(6));
        setCenterLat(lat);
        setCenterLng(lng);
        mapRef.current?.flyTo({ center: [lng, lat], zoom: Math.max(mapRef.current.getZoom(), 14), duration: 680 });
        setViewportDirty(true);
        setLocating(false);
      },
      (geoError) => {
        setError(`위치 정보를 가져오지 못했습니다: ${geoError.message}`);
        setLocating(false);
      },
      { timeout: 7000, maximumAge: 30_000 }
    );
  }

  function toggleSave(id: number) {
    setSavedIds((previous) => {
      const next = new Set(previous);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  function zoomBy(delta: number) {
    const map = mapRef.current;
    if (!map || !mapReadyRef.current) {
      return;
    }
    const center = map.getCenter();
    const nextZoom = Math.max(4, Math.min(18, map.getZoom() + delta));
    map.easeTo({
      center: [center.lng, center.lat],
      zoom: nextZoom,
      duration: 220,
      essential: true,
    });
  }

  function stopMapControlEvent(event: React.SyntheticEvent) {
    event.preventDefault();
    event.stopPropagation();
    const nativeEvent = event.nativeEvent as Event & { stopImmediatePropagation?: () => void };
    nativeEvent.stopImmediatePropagation?.();
  }

  function selectItem(id: number, focusMap: boolean) {
    setSelectedId(id);
    setPanelOpen(true);
    setMobileTab("map");

    if (!focusMap || !mapRef.current) {
      return;
    }
    const item = items.find((value) => value.id === id);
    if (!item) {
      return;
    }
    mapRef.current.flyTo({
      center: [item.lng, item.lat],
      zoom: Math.max(mapRef.current.getZoom(), 14),
      essential: true,
      duration: 620,
    });
  }

  function startSidebarResize(event: React.MouseEvent<HTMLDivElement>) {
    if (isCompactLayout) return;
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = sidebarWidth;

    const onMouseMove = (moveEvent: MouseEvent) => {
      const delta = moveEvent.clientX - startX;
      const nextWidth = Math.min(MAX_SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH, startWidth + delta));
      setSidebarWidth(nextWidth);
    };

    const onMouseUp = () => {
      window.removeEventListener("mousemove", onMouseMove);
      window.removeEventListener("mouseup", onMouseUp);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
    };

    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);
  }

  function startListPaneResize(event: React.MouseEvent<HTMLDivElement>) {
    if (!showDesktopDetail || !leftPanelRef.current) return;
    event.preventDefault();
    const panelHeight = leftPanelRef.current.getBoundingClientRect().height;
    const startY = event.clientY;
    const startPercent = listPanePercent;

    const onMouseMove = (moveEvent: MouseEvent) => {
      const deltaPercent = ((moveEvent.clientY - startY) / Math.max(panelHeight, 360)) * 100;
      const nextPercent = Math.min(MAX_LIST_PANE_PERCENT, Math.max(MIN_LIST_PANE_PERCENT, startPercent + deltaPercent));
      setListPanePercent(nextPercent);
    };

    const onMouseUp = () => {
      window.removeEventListener("mousemove", onMouseMove);
      window.removeEventListener("mouseup", onMouseUp);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
    };

    document.body.style.cursor = "row-resize";
    document.body.style.userSelect = "none";
    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);
  }

  const detailReviewCount = selectedItem?.review_count;
  const detailBlogReviewCount = detailReviewCount ? Math.max(1, Math.round(detailReviewCount * 0.18)) : null;

  const detailContent = (
    <>
      {detailLoading && <p className="notice info">상세 정보를 불러오는 중입니다...</p>}

      {detailError && (
        <div className="nearby-detail-error">
          <p>{detailError}</p>
          <button type="button" onClick={retryDetail}>다시 시도</button>
        </div>
      )}

      {!detailLoading && (
        <>
          {effectiveImages.length > 0 && (
            <div className="nearby-carousel">
              <img src={effectiveImages[imageIndex % effectiveImages.length]} alt="POI 이미지" />
              {effectiveImages.length > 1 && (
                <div className="nearby-carousel-nav">
                  <button
                    type="button"
                    onClick={() => setImageIndex((previous) => (previous - 1 + effectiveImages.length) % effectiveImages.length)}
                  >
                    이전
                  </button>
                  <span>{(imageIndex % effectiveImages.length) + 1} / {effectiveImages.length}</span>
                  <button
                    type="button"
                    onClick={() => setImageIndex((previous) => (previous + 1) % effectiveImages.length)}
                  >
                    다음
                  </button>
                </div>
              )}
            </div>
          )}

          <p className="nearby-detail-review">{reviewSummary(selectedItem?.rating_score, selectedItem?.review_count)}</p>
          {detailReviewCount && (
            <p className="nearby-detail-review-count">
              방문 리뷰 {detailReviewCount.toLocaleString()}
              {detailBlogReviewCount ? ` · 블로그 리뷰 ${detailBlogReviewCount.toLocaleString()}` : ""}
            </p>
          )}

          <p className="nearby-detail-address">{selectedDetail?.address ?? selectedItem?.preview?.address ?? "주소 정보 없음"}</p>
          <p className="nearby-detail-description">{selectedDetail?.description ?? selectedItem?.preview?.snippet ?? "소개 정보 없음"}</p>

          <div className="nearby-detail-actions">
            {selectedDetail?.links?.google && (
              <a href={selectedDetail.links.google} target="_blank" rel="noreferrer">길찾기</a>
            )}
            {selectedId != null && (
              <button type="button" onClick={() => toggleSave(selectedId)}>
                {savedIds.has(selectedId) ? "저장 해제" : "저장"}
              </button>
            )}
            {selectedDetail?.links?.naver && (
              <a href={selectedDetail.links.naver} target="_blank" rel="noreferrer">네이버 지도</a>
            )}
          </div>

          {selectedDetail && (
            <div className="nearby-related-wrap">
              <div>
                <h4>연관 숙소</h4>
                <ul>
                  {selectedDetail.related.properties.map((property) => (
                    <li key={property.property_id}>
                      <Link to={`/properties/${property.property_id}`}>
                        {property.name} {property.city ? `· ${property.city}` : ""}
                      </Link>
                    </li>
                  ))}
                  {selectedDetail.related.properties.length === 0 && <li>연관 숙소 없음</li>}
                </ul>
              </div>
              <div>
                <h4>연관 티켓/상품</h4>
                <ul>
                  {selectedDetail.related.products.map((product) => (
                    <li key={product.product_id}>
                      <Link to={`/tickets/${product.product_id}`}>{product.name}</Link>
                    </li>
                  ))}
                  {selectedDetail.related.products.length === 0 && <li>연관 상품 없음</li>}
                </ul>
              </div>
            </div>
          )}
        </>
      )}
    </>
  );

  return (
    <section className="page nearby-v2-page">
      <header className="page-head nearby-v2-head">
        <p className="page-kicker">NEARBY MAP DISCOVERY</p>
        <div className="page-title-wrap">
          <div>
            <h2>주변 추천 지도</h2>
            <p className="page-summary">
              지도와 리스트를 동시에 보며, 사용자가 지정한 영역 기준으로 재검색합니다.
            </p>
          </div>
          <div className="page-metrics" aria-label="주변 추천 지표">
            <div>
              <strong>{items.length}</strong>
              <span>현재 표시 결과</span>
            </div>
            <div>
              <strong>{radius}m</strong>
              <span>거리 필터</span>
            </div>
          </div>
        </div>
      </header>

      <section className="nearby-v2-toolbar">
        <label className="field-group">
          카테고리
          <select value={category} onChange={(event) => setCategory(event.target.value)}>
            {CATEGORY_OPTIONS.map((option) => (
              <option key={option.value || "all"} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label className="field-group">
          정렬
          <select value={sort} onChange={(event) => setSort(parseSort(event.target.value))}>
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label className="field-group">
          반경 필터 ({radius}m)
          {!radiusEnabledForViewport && " · 광역 뷰에서는 자동 해제"}
          <input
            type="range"
            min={500}
            max={10000}
            step={100}
            value={radius}
            onChange={(event) => setRadius(Number(event.target.value))}
          />
        </label>

        <label className="nearby-auto-search-toggle">
          <input
            type="checkbox"
            checked={autoSearch}
            onChange={(event) => setAutoSearch(event.target.checked)}
          />
          자동 검색(400ms debounce)
        </label>

        <button type="button" className="nearby-ghost-btn" onClick={useCurrentLocation} disabled={locating}>
          {locating ? "위치 확인 중..." : "현재 위치로 이동"}
        </button>
      </section>

      <section className="nearby-mobile-tabs" role="tablist" aria-label="지도/리스트 전환">
        <button
          type="button"
          role="tab"
          aria-selected={mobileTab === "map"}
          className={mobileTab === "map" ? "active" : ""}
          onClick={() => setMobileTab("map")}
        >
          지도
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mobileTab === "list"}
          className={mobileTab === "list" ? "active" : ""}
          onClick={() => setMobileTab("list")}
        >
          리스트
        </button>
      </section>

      <section className={`nearby-v2-layout ${mobileTab === "list" ? "mobile-list" : "mobile-map"}`}>
        <aside
          className="nearby-v2-list-panel"
          aria-label="주변 추천 목록"
          style={!isCompactLayout ? { width: `${sidebarWidth}px` } : undefined}
        >
          <div className={`nearby-left-stack ${showDesktopDetail ? "has-detail" : ""}`} ref={leftPanelRef}>
            <div
              className="nearby-list-pane"
              style={showDesktopDetail ? { flex: `0 0 ${listPanePercent}%` } : { flex: "1 1 auto" }}
            >
              {loading && <p className="notice info">주변 추천을 불러오는 중입니다...</p>}
              {error && <p className="notice error">{error}</p>}
              {!loading && !error && items.length === 0 && (
                <p className="notice warning">현재 화면/필터 조건에서 결과를 찾지 못했습니다.</p>
              )}

              <ul className="nearby-result-list">
                {items.map((item) => {
                  const selected = item.id === selectedId;
                  const hovered = item.id === hoveredId;
                  const saved = savedIds.has(item.id);
                  const thumbnail = item.preview?.thumbnail_url ?? buildFallbackImage(item.id);
                  return (
                    <li
                      key={item.id}
                      className={`nearby-result-card ${selected ? "selected" : ""} ${hovered ? "hovered" : ""}`}
                      onMouseEnter={() => setHoveredId(item.id)}
                      onMouseLeave={() => setHoveredId((previous) => (previous === selectedId ? previous : null))}
                    >
                      <button
                        type="button"
                        className="nearby-card-main"
                        onClick={() => {
                          selectItem(item.id, true);
                        }}
                      >
                        <img src={thumbnail} alt={`${item.name} 썸네일`} />
                        <div>
                          <p className="nearby-card-category">{categoryLabel(item.category)}</p>
                          <h3>{item.name}</h3>
                          <p>{item.distance_m.toLocaleString()}m · {item.preview?.address ?? "주소 정보 없음"}</p>
                          <p className="nearby-card-review">{reviewSummary(item.rating_score, item.review_count)}</p>
                          <p className="nearby-card-snippet">{item.preview?.snippet ?? "소개 정보 준비중입니다."}</p>
                        </div>
                      </button>
                      <div className="nearby-card-actions">
                        <button type="button" className={saved ? "active" : ""} onClick={() => toggleSave(item.id)}>
                          {saved ? "저장됨" : "저장"}
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            selectItem(item.id, true);
                          }}
                        >
                          상세보기
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </div>

            {showDesktopDetail && (
              <>
                <div
                  className="nearby-list-detail-divider"
                  role="separator"
                  aria-orientation="horizontal"
                  onMouseDown={startListPaneResize}
                />
                <article className="nearby-left-detail-pane" style={{ flex: `0 0 ${100 - listPanePercent}%` }}>
                  <header className="nearby-left-detail-head">
                    <div>
                      <p>{categoryLabel(selectedItem?.category ?? selectedDetail?.category)}</p>
                      <h3>{selectedItem?.name ?? selectedDetail?.name ?? "POI 상세"}</h3>
                      <span>{selectedItem ? `${selectedItem.distance_m.toLocaleString()}m` : "거리 정보 없음"}</span>
                    </div>
                    <button type="button" onClick={closePanel} aria-label="상세 패널 닫기">닫기</button>
                  </header>
                  {detailContent}
                </article>
              </>
            )}
          </div>
        </aside>

        {!isCompactLayout && (
          <div
            className="nearby-panel-divider"
            role="separator"
            aria-orientation="vertical"
            onMouseDown={startSidebarResize}
          />
        )}

        <section className="nearby-v2-map-panel" aria-label="주변 추천 지도">
          <div ref={mapContainerRef} className="nearby-map-canvas" />

          <div
            className="nearby-zoom-controls"
            aria-label="지도 확대/축소"
            onMouseDown={stopMapControlEvent}
            onClick={stopMapControlEvent}
            onDoubleClick={stopMapControlEvent}
            onPointerDown={stopMapControlEvent}
          >
            <button
              type="button"
              onClick={(event) => {
                stopMapControlEvent(event);
                zoomBy(1);
              }}
              aria-label="지도 확대"
            >
              +
            </button>
            <button
              type="button"
              onClick={(event) => {
                stopMapControlEvent(event);
                zoomBy(-1);
              }}
              aria-label="지도 축소"
            >
              -
            </button>
          </div>

          {viewportDirty && (
            <button
              type="button"
              className="nearby-search-area-btn"
              onMouseDown={stopMapControlEvent}
              onPointerDown={stopMapControlEvent}
              onClick={(event) => {
                stopMapControlEvent(event);
                void runNearbySearch();
              }}
            >
              이 영역에서 재검색
            </button>
          )}

          {meta && (
            <p className="nearby-meta-pill">
              returned {meta.returned} · {meta.has_more ? "추가 결과 있음" : "마지막 결과"}
            </p>
          )}

          {!showDesktopDetail && (
            <article className={`nearby-detail-sheet ${panelOpen ? "open" : ""}`}>
              <header>
                <div>
                  <p>{categoryLabel(selectedItem?.category ?? selectedDetail?.category)}</p>
                  <h3>{selectedItem?.name ?? selectedDetail?.name ?? "POI 상세"}</h3>
                  <span>{selectedItem ? `${selectedItem.distance_m.toLocaleString()}m` : "거리 정보 없음"}</span>
                </div>
                <button type="button" onClick={closePanel} aria-label="상세 패널 닫기">닫기</button>
              </header>
              {detailContent}
            </article>
          )}
        </section>
      </section>
    </section>
  );
}
