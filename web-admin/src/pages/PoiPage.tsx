import { FormEvent, useEffect, useRef, useState } from "react";
import maplibregl, { Map as MapLibreMap, Marker } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { apiGet, apiPatch, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };

type AdminPoiSummary = {
  id: number;
  name: string;
  category?: string;
  city?: string;
  lat: number;
  lng: number;
  address?: string;
  active: boolean;
};

type AdminPoiDetail = {
  id: number;
  name: string;
  category?: string;
  city?: string;
  lat: number;
  lng: number;
  address?: string;
  description?: string;
  images: string[];
  popularity_score: number;
  rating_score: number;
  active: boolean;
};

type AdminPoiListData = {
  items: AdminPoiSummary[];
  has_more: boolean;
};

type PoiForm = {
  name: string;
  category: string;
  city: string;
  lat: number;
  lng: number;
  address: string;
  description: string;
  imageUrlsText: string;
  popularity: number;
  rating: number;
  active: boolean;
};

const DEFAULT_FORM: PoiForm = {
  name: "",
  category: "attraction",
  city: "Seoul",
  lat: 37.501,
  lng: 127.0396,
  address: "",
  description: "",
  imageUrlsText: "",
  popularity: 0,
  rating: 0,
  active: true,
};

function toErrorMessage(error: unknown, fallback: string): string {
  const apiError = error as ApiError;
  if (apiError?.code || apiError?.message) {
    return `${apiError.code ?? "ERROR"}: ${apiError.message ?? fallback}`;
  }
  if (error instanceof Error) {
    return `${fallback}: ${error.message}`;
  }
  return fallback;
}

function parseImageInput(value: string): string[] {
  return value
    .split(/\r?\n|,/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function PoiPage() {
  const [items, setItems] = useState<AdminPoiSummary[]>([]);
  const [keyword, setKeyword] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [form, setForm] = useState<PoiForm>(DEFAULT_FORM);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markerRef = useRef<Marker | null>(null);

  async function loadList() {
    setLoading(true);
    setError(null);
    try {
      const query = new URLSearchParams({ limit: "200", offset: "0" });
      if (keyword.trim()) {
        query.set("keyword", keyword.trim());
      }
      const response = await apiGet<AdminPoiListData>(`/v1/admin/poi?${query.toString()}`);
      setItems(response.data.items ?? []);
    } catch (loadError) {
      setError(toErrorMessage(loadError, "POI 목록을 불러오지 못했습니다."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadList();
  }, [keyword]);

  useEffect(() => {
    if (!mapContainerRef.current || mapRef.current) {
      return;
    }

    const map = new maplibregl.Map({
      container: mapContainerRef.current,
      style: "https://demotiles.maplibre.org/style.json",
      center: [form.lng, form.lat],
      zoom: 12,
      minZoom: 4,
      maxZoom: 18,
    });

    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

    const marker = new maplibregl.Marker({ color: "#0f7b6c", draggable: true })
      .setLngLat([form.lng, form.lat])
      .addTo(map);

    marker.on("dragend", () => {
      const position = marker.getLngLat();
      setForm((previous) => ({
        ...previous,
        lat: Number(position.lat.toFixed(6)),
        lng: Number(position.lng.toFixed(6)),
      }));
    });

    markerRef.current = marker;
    mapRef.current = map;

    return () => {
      marker.remove();
      map.remove();
      markerRef.current = null;
      mapRef.current = null;
    };
  }, [form.lat, form.lng]);

  useEffect(() => {
    if (!mapRef.current || !markerRef.current) {
      return;
    }

    markerRef.current.setLngLat([form.lng, form.lat]);
    mapRef.current.easeTo({ center: [form.lng, form.lat], duration: 380 });
  }, [form.lat, form.lng]);

  async function selectPoi(poiId: number) {
    setSelectedId(poiId);
    setStatus(null);
    setError(null);
    try {
      const response = await apiGet<AdminPoiDetail>(`/v1/admin/poi/${poiId}`);
      const detail = response.data;
      setForm({
        name: detail.name,
        category: detail.category ?? "attraction",
        city: detail.city ?? "",
        lat: detail.lat,
        lng: detail.lng,
        address: detail.address ?? "",
        description: detail.description ?? "",
        imageUrlsText: detail.images.join("\n"),
        popularity: detail.popularity_score,
        rating: detail.rating_score,
        active: detail.active,
      });
    } catch (loadError) {
      setError(toErrorMessage(loadError, "POI 상세를 불러오지 못했습니다."));
    }
  }

  function startCreate() {
    setSelectedId(null);
    setForm(DEFAULT_FORM);
    setStatus(null);
    setError(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setStatus(null);
    setError(null);

    const payload = {
      name: form.name.trim(),
      category: form.category.trim() || null,
      city: form.city.trim() || null,
      lat: form.lat,
      lng: form.lng,
      address: form.address.trim() || null,
      description: form.description.trim() || null,
      images: parseImageInput(form.imageUrlsText),
      popularity_score: Math.max(0, Math.round(form.popularity)),
      rating_score: Number(form.rating.toFixed(2)),
      active: form.active,
    };

    try {
      if (!payload.name) {
        setError("VALIDATION_ERROR: 이름은 필수입니다.");
        setSaving(false);
        return;
      }

      if (selectedId == null) {
        const response = await apiPost<{ poi_id: number }>("/v1/admin/poi", payload);
        setSelectedId(response.data.poi_id);
        setStatus(`POI #${response.data.poi_id} 생성 완료`);
      } else {
        await apiPatch<{ poi_id: number; updated: boolean }>(`/v1/admin/poi/${selectedId}`, payload);
        setStatus(`POI #${selectedId} 수정 완료`);
      }
      await loadList();
    } catch (submitError) {
      setError(toErrorMessage(submitError, "POI 저장 실패"));
    } finally {
      setSaving(false);
    }
  }

  async function quickToggle(summary: AdminPoiSummary) {
    try {
      await apiPatch(`/v1/admin/poi/${summary.id}`, { active: !summary.active });
      if (selectedId === summary.id) {
        setForm((previous) => ({ ...previous, active: !summary.active }));
      }
      await loadList();
    } catch (toggleError) {
      setError(toErrorMessage(toggleError, "활성 상태를 바꾸지 못했습니다."));
    }
  }

  return (
    <div className="poi-admin-page">
      <div className="poi-admin-header">
        <h2>POI 운영</h2>
        <p>등록/수정/비공개 처리와 지도 미리보기를 같은 화면에서 관리합니다.</p>
      </div>

      <div className="row-form">
        <input
          value={keyword}
          placeholder="이름/카테고리/도시 검색"
          onChange={(event) => setKeyword(event.target.value)}
        />
        <button type="button" onClick={startCreate}>새 POI 등록</button>
      </div>

      <div className="poi-admin-layout">
        <aside className="poi-admin-list">
          {loading && <p>목록 로딩 중...</p>}
          {!loading && items.length === 0 && <p>조건에 맞는 POI가 없습니다.</p>}
          <ul>
            {items.map((item) => (
              <li key={item.id} className={item.id === selectedId ? "active" : ""}>
                <button type="button" className="poi-row-main" onClick={() => void selectPoi(item.id)}>
                  <strong>#{item.id} {item.name}</strong>
                  <span>{item.category ?? "기타"} · {item.city ?? "-"}</span>
                  <span>{item.active ? "공개" : "비공개"}</span>
                </button>
                <button type="button" className="poi-row-toggle" onClick={() => void quickToggle(item)}>
                  {item.active ? "비공개" : "공개"}
                </button>
              </li>
            ))}
          </ul>
        </aside>

        <section className="poi-admin-editor">
          <form onSubmit={submit} className="poi-form-grid">
            <label>
              이름
              <input
                value={form.name}
                onChange={(event) => setForm((previous) => ({ ...previous, name: event.target.value }))}
              />
            </label>

            <label>
              카테고리
              <select
                value={form.category}
                onChange={(event) => setForm((previous) => ({ ...previous, category: event.target.value }))}
              >
                <option value="attraction">관광</option>
                <option value="food">맛집</option>
                <option value="shopping">쇼핑</option>
                <option value="museum">전시</option>
              </select>
            </label>

            <label>
              도시
              <input
                value={form.city}
                onChange={(event) => setForm((previous) => ({ ...previous, city: event.target.value }))}
              />
            </label>

            <label>
              위도
              <input
                type="number"
                step="0.000001"
                value={form.lat}
                onChange={(event) => setForm((previous) => ({ ...previous, lat: Number(event.target.value) }))}
              />
            </label>

            <label>
              경도
              <input
                type="number"
                step="0.000001"
                value={form.lng}
                onChange={(event) => setForm((previous) => ({ ...previous, lng: Number(event.target.value) }))}
              />
            </label>

            <label>
              주소
              <input
                value={form.address}
                onChange={(event) => setForm((previous) => ({ ...previous, address: event.target.value }))}
              />
            </label>

            <label className="poi-full-row">
              설명
              <textarea
                value={form.description}
                onChange={(event) => setForm((previous) => ({ ...previous, description: event.target.value }))}
              />
            </label>

            <label className="poi-full-row">
              이미지 URL (쉼표/줄바꿈 구분)
              <textarea
                value={form.imageUrlsText}
                onChange={(event) => setForm((previous) => ({ ...previous, imageUrlsText: event.target.value }))}
              />
            </label>

            <label>
              popularity_score
              <input
                type="number"
                min={0}
                value={form.popularity}
                onChange={(event) => setForm((previous) => ({ ...previous, popularity: Number(event.target.value) }))}
              />
            </label>

            <label>
              rating_score
              <input
                type="number"
                min={0}
                max={5}
                step={0.1}
                value={form.rating}
                onChange={(event) => setForm((previous) => ({ ...previous, rating: Number(event.target.value) }))}
              />
            </label>

            <label className="poi-active-toggle">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(event) => setForm((previous) => ({ ...previous, active: event.target.checked }))}
              />
              공개 상태(active)
            </label>

            <div className="poi-form-actions">
              <button type="submit" disabled={saving}>{saving ? "저장 중..." : selectedId == null ? "POI 생성" : "POI 수정"}</button>
            </div>
          </form>

          {status && <p className="success">{status}</p>}
          {error && <p className="error">{error}</p>}

          <div className="poi-map-preview" ref={mapContainerRef} />
          <p className="poi-map-hint">좌표 입력 또는 마커 드래그 시 지도 미리보기가 즉시 갱신됩니다.</p>
        </section>
      </div>
    </div>
  );
}
