import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiGet, apiPost } from "../api/client";

type Property = { property_id: number; name: string; city?: string };

export function PropertiesPage() {
  const [items, setItems] = useState<Property[]>([]);
  const [name, setName] = useState("Wanderly Hotel Seoul");
  const [city, setCity] = useState("Seoul");

  async function load() {
    const res = await apiGet<{ items: Property[] }>("/v1/properties?limit=30");
    setItems(res.data.items);
  }

  useEffect(() => {
    load();
  }, []);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
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
    await load();
  }

  return (
    <div>
      <h2>숙소 관리</h2>
      <form onSubmit={onSubmit} className="row-form">
        <input value={name} onChange={(e) => setName(e.target.value)} />
        <input value={city} onChange={(e) => setCity(e.target.value)} />
        <button type="submit">숙소 생성</button>
      </form>
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
