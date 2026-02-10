import { useEffect, useState } from "react";
import { Link, NavLink, Route, Routes } from "react-router-dom";
import { HomePage } from "./pages/HomePage";
import { SearchPage } from "./pages/SearchPage";
import { PropertyPage } from "./pages/PropertyPage";
import { TicketsPage } from "./pages/TicketsPage";
import { TicketDetailPage } from "./pages/TicketDetailPage";
import { PackagesPage } from "./pages/PackagesPage";
import { PackageDetailPage } from "./pages/PackageDetailPage";
import { ChatPage } from "./pages/ChatPage";
import { NearbyPage } from "./pages/NearbyPage";
import { CheckoutBookingPage } from "./pages/CheckoutBookingPage";
import { BookingCompletePage } from "./pages/BookingCompletePage";
import { CheckoutTicketPage } from "./pages/CheckoutTicketPage";
import { CheckoutPackagePage } from "./pages/CheckoutPackagePage";
import { LoginPage } from "./pages/LoginPage";
import { MyReservationsPage } from "./pages/MyReservationsPage";
import { RequireAuth } from "./components/RequireAuth";
import { clearAuthSession, getAuthUser, subscribeAuthChange } from "./auth/session";
import { apiPost } from "./api/client";

const navItems = [
  { to: "/search", label: "숙소" },
  { to: "/tickets", label: "티켓" },
  { to: "/packages", label: "패키지" },
  { to: "/nearby", label: "주변 추천" },
  { to: "/chat", label: "AI 컨시어지" },
];

export function App() {
  const [authUser, setAuthUser] = useState(() => getAuthUser());

  useEffect(() => {
    return subscribeAuthChange(() => {
      setAuthUser(getAuthUser());
    });
  }, []);

  async function handleLogout() {
    try {
      await apiPost("/v1/auth/logout", {}, { Authorization: "" });
    } catch {
      // Best effort logout: clear local session even if network call fails.
    } finally {
      clearAuthSession();
    }
  }

  return (
    <div className="app-shell">
      <header className="site-header">
        <div className="utility-bar">
          <p>국내외 프리미엄 숙소 얼리버드 최대 18% 단독 혜택</p>
          <div className="utility-links">
            <button type="button">KRW</button>
            <button type="button">한국어</button>
            <button type="button">고객센터</button>
          </div>
        </div>
        <div className="top-nav">
          <Link to="/" className="brand">
            <span className="brand-mark">StayVista</span>
            <span className="brand-sub">Premium Escapes</span>
          </Link>
          <nav className="primary-nav" aria-label="메인 메뉴">
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="header-actions">
            {authUser ? (
              <>
                <Link to="/my/reservations" className="outline-btn">내 예약</Link>
                <span className="user-chip">{authUser.name} #{authUser.userId}</span>
                <button type="button" className="plain-link" onClick={handleLogout}>로그아웃</button>
              </>
            ) : (
              <>
                <Link to="/search" className="outline-btn">숙소 등록</Link>
                <Link to="/login" className="plain-link">로그인</Link>
                <Link to="/login?mode=register" className="pill-btn">회원가입</Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="app-main">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/properties/:id" element={<PropertyPage />} />
          <Route path="/tickets" element={<TicketsPage />} />
          <Route path="/tickets/:id" element={<TicketDetailPage />} />
          <Route path="/packages" element={<PackagesPage />} />
          <Route path="/packages/:id" element={<PackageDetailPage />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/nearby" element={<NearbyPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/my/reservations" element={<RequireAuth><MyReservationsPage /></RequireAuth>} />
          <Route path="/checkout/booking" element={<RequireAuth><CheckoutBookingPage /></RequireAuth>} />
          <Route path="/checkout/ticket" element={<RequireAuth><CheckoutTicketPage /></RequireAuth>} />
          <Route path="/checkout/package" element={<RequireAuth><CheckoutPackagePage /></RequireAuth>} />
          <Route path="/booking/complete" element={<RequireAuth><BookingCompletePage /></RequireAuth>} />
          <Route path="*" element={<HomePage />} />
        </Routes>
      </main>
    </div>
  );
}
