import { Link, Route, Routes } from "react-router-dom";
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

export function App() {
  return (
    <div className="app-shell">
      <header className="top-nav">
        <Link to="/" className="brand">Wanderly</Link>
        <nav>
          <Link to="/search">숙소</Link>
          <Link to="/tickets">티켓</Link>
          <Link to="/packages">패키지</Link>
          <Link to="/chat">챗봇</Link>
          <Link to="/nearby">주변추천</Link>
        </nav>
      </header>
      <main>
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
          <Route path="/checkout/booking" element={<CheckoutBookingPage />} />
          <Route path="/checkout/ticket" element={<CheckoutTicketPage />} />
          <Route path="/checkout/package" element={<CheckoutPackagePage />} />
          <Route path="/booking/complete" element={<BookingCompletePage />} />
          <Route path="*" element={<HomePage />} />
        </Routes>
      </main>
    </div>
  );
}
