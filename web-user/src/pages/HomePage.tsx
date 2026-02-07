import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";

export function HomePage() {
  const navigate = useNavigate();
  const [city, setCity] = useState("Seoul");
  const [checkIn, setCheckIn] = useState("2026-02-10");
  const [checkOut, setCheckOut] = useState("2026-02-12");
  const [adults, setAdults] = useState(2);
  const [children, setChildren] = useState(0);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    const query = new URLSearchParams({
      city,
      check_in: checkIn,
      check_out: checkOut,
      adults: String(adults),
      children: String(children),
    });
    navigate(`/search?${query.toString()}`);
  };

  return (
    <section className="hero">
      <h1>여행의 리듬을 찾는 가장 빠른 방법</h1>
      <p>숙소, 티켓, 패키지를 한 화면에서 탐색해 보세요.</p>
      <form className="search-form" onSubmit={onSubmit}>
        <label>
          도시
          <select value={city} onChange={(e) => setCity(e.target.value)} aria-label="도시 선택">
            <option value="Seoul">Seoul</option>
            <option value="Busan">Busan</option>
            <option value="Jeju">Jeju</option>
            <option value="Daegu">Daegu</option>
          </select>
        </label>
        <label>체크인<input type="date" value={checkIn} onChange={(e) => setCheckIn(e.target.value)} /></label>
        <label>체크아웃<input type="date" value={checkOut} onChange={(e) => setCheckOut(e.target.value)} /></label>
        <label>성인<input type="number" min={1} value={adults} onChange={(e) => setAdults(Number(e.target.value))} /></label>
        <label>어린이<input type="number" min={0} value={children} onChange={(e) => setChildren(Number(e.target.value))} /></label>
        <button type="submit">검색</button>
      </form>
    </section>
  );
}
