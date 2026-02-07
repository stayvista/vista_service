import { useEffect, useState } from "react";
import { apiGet } from "../api/client";

type Poi = { poi_id: string; name: string; category?: string; distance_m: number };

export function NearbyPage() {
  const [items, setItems] = useState<Poi[]>([]);
  const [category, setCategory] = useState("attraction");

  useEffect(() => {
    apiGet<{ items: Poi[] }>(`/v1/geo/pois/nearby?lat=37.501&lng=127.0396&radius_m=2000&limit=20&category=${category}`)
      .then((res) => setItems(res.data.items));
  }, [category]);

  return (
    <section className="page">
      <h2>주변 추천</h2>
      <div className="toolbar">
        <select value={category} onChange={(e) => setCategory(e.target.value)}>
          <option value="attraction">관광</option>
          <option value="food">맛집</option>
          <option value="shopping">쇼핑</option>
          <option value="museum">전시</option>
        </select>
      </div>
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
