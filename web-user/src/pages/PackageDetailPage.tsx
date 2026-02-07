import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiGet } from "../api/client";

type PackageDetail = {
  package_id: number;
  name: string;
  status: string;
  components: Array<{
    type: string;
    room_type_id?: number;
    event_id?: number;
    nights?: number;
    rooms?: number;
    quantity?: number;
  }>;
  price: {
    currency: string;
    amount_total: number;
  };
};

export function PackageDetailPage() {
  const { id } = useParams();
  const [item, setItem] = useState<PackageDetail | null>(null);

  useEffect(() => {
    if (!id) return;
    apiGet<PackageDetail>(`/v1/packages/${id}`).then((res) => setItem(res.data));
  }, [id]);

  return (
    <section className="page">
      <h2>{item?.name ?? "패키지 상세"}</h2>
      <p className={item?.status === "ACTIVE" ? "success" : "warning"}>{item?.status ?? "UNKNOWN"}</p>
      <p>
        총액: {item?.price.amount_total ?? 0} {item?.price.currency ?? "KRW"}
      </p>
      <ul className="card-list">
        {item?.components?.map((c, idx) => (
          <li key={`${c.type}-${idx}`} className="card">
            <h3>{c.type}</h3>
            {c.room_type_id && <p>room_type_id: {c.room_type_id}</p>}
            {c.event_id && <p>event_id: {c.event_id}</p>}
            {c.nights != null && <p>nights: {c.nights}</p>}
            {c.rooms != null && <p>rooms: {c.rooms}</p>}
            {c.quantity != null && <p>quantity: {c.quantity}</p>}
          </li>
        ))}
      </ul>
      <p>패키지 확정 중 일부 단계 실패 시 서버에서 자동 보상되어 부분 구매가 남지 않습니다.</p>
      <Link to={`/checkout/package?package_id=${id}`}>구매하기</Link>
    </section>
  );
}
