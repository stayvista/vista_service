import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiGet } from "../api/client";

type PackageDetail = { package_id: number; name: string; components: Array<{ type: string }> };

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
      <ul>
        {item?.components?.map((c, idx) => <li key={idx}>{c.type}</li>)}
      </ul>
      <Link to={`/checkout/package?package_id=${id}`}>구매하기</Link>
    </section>
  );
}
