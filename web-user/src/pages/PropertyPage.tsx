import { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { apiGet } from "../api/client";

type Property = { property_id: number; name: string; city?: string; address1?: string };
type RoomType = { room_type_id: number; name: string; max_guests: number; base_price: { amount: number } };

export function PropertyPage() {
  const { id } = useParams();
  const [search] = useSearchParams();
  const [property, setProperty] = useState<Property | null>(null);
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);

  useEffect(() => {
    if (!id) return;
    apiGet<Property>(`/v1/properties/${id}`).then((res) => setProperty(res.data));
    apiGet<{ items: RoomType[] }>(`/v1/properties/${id}/room-types`).then((res) => setRoomTypes(res.data.items));
  }, [id]);

  return (
    <section className="page">
      <h2>{property?.name ?? "숙소 상세"}</h2>
      <p>{property?.city} {property?.address1}</p>
      <ul className="card-list">
        {roomTypes.map((room) => (
          <li className="card" key={room.room_type_id}>
            <h3>{room.name}</h3>
            <p>정원 {room.max_guests}명</p>
            <p>기준가 {room.base_price?.amount ?? 0} KRW</p>
            <Link to={`/checkout/booking?room_type_id=${room.room_type_id}&check_in=${search.get("check_in") ?? "2026-02-10"}&check_out=${search.get("check_out") ?? "2026-02-12"}`}>예약하기</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
