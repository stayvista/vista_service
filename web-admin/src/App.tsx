import { FormEvent, useState } from "react";
import { Link, Route, Routes } from "react-router-dom";
import { clearAdminId, getAdminId, setAdminId } from "./api/client";
import { PropertiesPage } from "./pages/PropertiesPage";
import { PropertyDetailPage } from "./pages/PropertyDetailPage";
import { InventoryPage } from "./pages/InventoryPage";
import { TicketsPage } from "./pages/TicketsPage";
import { VouchersPage } from "./pages/VouchersPage";
import { OpsPage } from "./pages/OpsPage";
import { PackagesPage } from "./pages/PackagesPage";

export function App() {
  const [adminId, setAdminIdState] = useState(getAdminId());
  const [inputAdminId, setInputAdminId] = useState(adminId || "9001");

  function onLogin(e: FormEvent) {
    e.preventDefault();
    if (!/^\d+$/.test(inputAdminId)) return;
    setAdminId(inputAdminId);
    setAdminIdState(inputAdminId);
  }

  function logout() {
    clearAdminId();
    setAdminIdState("");
  }

  if (!adminId) {
    return (
      <div className="admin-login-wrap">
        <form className="admin-login-card" onSubmit={onLogin}>
          <h1>Wanderly Admin</h1>
          <p>운영자 ID(X-Admin-Id)를 입력해 시작하세요.</p>
          <input
            value={inputAdminId}
            onChange={(e) => setInputAdminId(e.target.value)}
            placeholder="예: 9001"
            inputMode="numeric"
          />
          <button type="submit" disabled={!/^\d+$/.test(inputAdminId)}>로그인</button>
        </form>
      </div>
    );
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>Wanderly Admin</h1>
        <nav>
          <Link to="/admin/properties">숙소</Link>
          <Link to="/admin/inventory">재고</Link>
          <Link to="/admin/tickets">티켓</Link>
          <Link to="/admin/packages">패키지</Link>
          <Link to="/admin/vouchers">바우처</Link>
          <Link to="/admin/ops">운영도구</Link>
        </nav>
      </aside>
      <section className="content">
        <header className="topbar">
          <span className="env-badge">LOCAL</span>
          <span>admin #{adminId}</span>
          <button type="button" onClick={logout}>로그아웃</button>
        </header>
        <Routes>
          <Route path="/admin/properties" element={<PropertiesPage />} />
          <Route path="/admin/properties/:id" element={<PropertyDetailPage />} />
          <Route path="/admin/inventory" element={<InventoryPage />} />
          <Route path="/admin/tickets" element={<TicketsPage />} />
          <Route path="/admin/packages" element={<PackagesPage />} />
          <Route path="/admin/vouchers" element={<VouchersPage />} />
          <Route path="/admin/ops" element={<OpsPage />} />
          <Route path="*" element={<PropertiesPage />} />
        </Routes>
      </section>
    </div>
  );
}
