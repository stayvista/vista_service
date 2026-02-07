import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";

type Property = { property_id: number; name: string; city?: string };
type ApiError = { code?: string; message?: string };

export function PropertiesPage() {
  const [items, setItems] = useState<Property[]>([]);
  const [name, setName] = useState("Wanderly Hotel Seoul");
  const [city, setCity] = useState("Seoul");
  const [searchCity, setSearchCity] = useState("");
  const [keyword, setKeyword] = useState("");
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    const query = new URLSearchParams({ limit: "50" });
    if (searchCity) query.set("city", searchCity);
    if (keyword) query.set("keyword", keyword);
    const res = await apiGet<{ items: Property[] }>(`/v1/properties?${query.toString()}`);
    setItems(res.data.items ?? []);
  }

  useEffect(() => {
    void load();
  }, [searchCity, keyword]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setStatusMessage(null);
    try {
      await apiPost("/v1/admin/properties", {
        partner_id: 1,
        name,
        country: "KR",
        city,
        address1: "Teheran-ro",
        lat: 37.501,
        lng: 127.0396,
        status: "ACTIVE",
      });
      setStatusMessage("숙소가 생성되었습니다.");
      await load();
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "숙소 생성 실패"}`);
    }
  }

  return (
    <div>
      <h2>숙소 관리</h2>
      <div className="row-form">
        <input
          value={searchCity}
          placeholder="도시 필터"
          onChange={(e) => setSearchCity(e.target.value)}
        />
        <input
          value={keyword}
          placeholder="키워드 필터"
          onChange={(e) => setKeyword(e.target.value)}
        />
      </div>
      <form onSubmit={onSubmit} className="row-form">
        <input value={name} onChange={(e) => setName(e.target.value)} />
        <input value={city} onChange={(e) => setCity(e.target.value)} />
        <button type="submit">숙소 생성</button>
      </form>
      {statusMessage && <p className="success">{statusMessage}</p>}
      {error && <p className="error">{error}</p>}
      <ul className="table">
        {items.map((item) => (
          <li key={item.property_id}>
            <span>{item.name}</span>
            <span>{item.city}</span>
            <Link to={`/admin/properties/${item.property_id}`}>수정</Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
