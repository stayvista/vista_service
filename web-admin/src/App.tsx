import { Link, Route, Routes } from "react-router-dom";
import { PropertiesPage } from "./pages/PropertiesPage";
import { PropertyDetailPage } from "./pages/PropertyDetailPage";
import { InventoryPage } from "./pages/InventoryPage";
import { TicketsPage } from "./pages/TicketsPage";
import { VouchersPage } from "./pages/VouchersPage";

export function App() {
  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>Wanderly Admin</h1>
        <nav>
          <Link to="/admin/properties">숙소</Link>
          <Link to="/admin/inventory">재고</Link>
          <Link to="/admin/tickets">티켓</Link>
          <Link to="/admin/vouchers">바우처</Link>
        </nav>
      </aside>
      <section className="content">
        <Routes>
          <Route path="/admin/properties" element={<PropertiesPage />} />
          <Route path="/admin/properties/:id" element={<PropertyDetailPage />} />
          <Route path="/admin/inventory" element={<InventoryPage />} />
          <Route path="/admin/tickets" element={<TicketsPage />} />
          <Route path="/admin/vouchers" element={<VouchersPage />} />
          <Route path="*" element={<PropertiesPage />} />
        </Routes>
      </section>
    </div>
  );
}
