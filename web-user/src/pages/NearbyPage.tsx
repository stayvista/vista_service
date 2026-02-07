import { useEffect, useMemo, useRef, useState } from "react";
import { apiGet } from "../api/client";

type Poi = { poi_id: string; name: string; category?: string; distance_m: number };
type PoiResponse = { items: Poi[] };
type CacheEntry = { fetchedAt: number; data: Poi[] };

export function NearbyPage() {
  const [items, setItems] = useState<Poi[]>([]);
  const [category, setCategory] = useState("attraction");
  const [lat, setLat] = useState(37.501);
  const [lng, setLng] = useState(127.0396);
  const [radius, setRadius] = useState(2000);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [locationState, setLocationState] = useState("기본 좌표 사용 중");
  const cacheRef = useRef<Map<string, CacheEntry>>(new Map());

  const cacheKey = useMemo(
    () => `${category}:${lat.toFixed(4)}:${lng.toFixed(4)}:${radius}`,
    [category, lat, lng, radius]
  );

  useEffect(() => {
    const cached = cacheRef.current.get(cacheKey);
    if (cached && Date.now() - cached.fetchedAt < 30_000) {
      setItems(cached.data);
      return;
    }

    setLoading(true);
    setError(null);
    apiGet<PoiResponse>(
      `/v1/geo/pois/nearby?lat=${lat}&lng=${lng}&radius_m=${radius}&limit=20&category=${category}`
    )
      .then((res) => {
        setItems(res.data.items);
        cacheRef.current.set(cacheKey, { fetchedAt: Date.now(), data: res.data.items });
      })
      .catch((e: unknown) => {
        const err = e as { code?: string; message?: string };
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "주변 조회 실패"}`);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [cacheKey, category, lat, lng, radius]);

  function requestLocation() {
    if (!navigator.geolocation) {
      setLocationState("브라우저 위치 기능을 지원하지 않습니다.");
      return;
    }
    setLocationState("현재 위치 요청 중");
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setLat(Number(position.coords.latitude.toFixed(6)));
        setLng(Number(position.coords.longitude.toFixed(6)));
        setLocationState("현재 위치 적용됨");
      },
      (geoError) => {
        setLocationState(`위치 권한 실패: ${geoError.message}`);
      },
      { enableHighAccuracy: false, timeout: 5000, maximumAge: 30_000 }
    );
  }

  return (
    <section className="page">
      <h2>주변 추천</h2>
      <div className="toolbar">
        <button type="button" onClick={requestLocation}>현재 위치 사용</button>
        <select value={category} onChange={(e) => setCategory(e.target.value)}>
          <option value="attraction">관광</option>
          <option value="food">맛집</option>
          <option value="shopping">쇼핑</option>
          <option value="museum">전시</option>
        </select>
        <input
          type="number"
          step="0.0001"
          value={lat}
          onChange={(e) => setLat(Number(e.target.value))}
          aria-label="위도"
        />
        <input
          type="number"
          step="0.0001"
          value={lng}
          onChange={(e) => setLng(Number(e.target.value))}
          aria-label="경도"
        />
        <input
          type="number"
          value={radius}
          min={200}
          max={10000}
          onChange={(e) => setRadius(Number(e.target.value))}
          aria-label="반경"
        />
      </div>
      <p>{locationState}</p>
      {loading && <p>주변 추천 불러오는 중...</p>}
      {error && <p className="error">{error}</p>}
      <ul className="card-list">
        {items.map((poi) => (
          <li key={poi.poi_id} className="card">
            <h3>{poi.name}</h3>
            <p>{poi.category} · {poi.distance_m}m</p>
          </li>
        ))}
      </ul>
    </section>
  );
}
