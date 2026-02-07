import { FormEvent, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { apiPost } from "../api/client";

type Card = {
  type: string;
  title: string;
  why?: string;
  property_id?: number;
  product_id?: number;
  package_id?: number;
  poi_id?: string;
};

type ChatResponse = {
  answer: string;
  cards: Card[];
  followups: string[];
};

type ChatMessage = {
  role: "user" | "assistant";
  text: string;
};

type ApiError = {
  code?: string;
  message?: string;
};

export function ChatPage() {
  const [message, setMessage] = useState("3박4일 서울 여행 추천해줘");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [answer, setAnswer] = useState<string>("");
  const [cards, setCards] = useState<Card[]>([]);
  const [followups, setFollowups] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = useMemo(() => message.trim().length > 0 && !loading, [loading, message]);

  async function ask(input: string) {
    setLoading(true);
    setError(null);
    setMessages((prev) => [...prev, { role: "user", text: input }]);
    try {
      const res = await apiPost<ChatResponse>("/v1/chat/recommend", {
        message: input,
        context: { city: "Seoul", days: 4, budget_krw: 800000, companions: "COUPLE" },
      });
      setAnswer(res.data.answer);
      setCards(res.data.cards ?? []);
      setFollowups(res.data.followups ?? []);
      setMessages((prev) => [...prev, { role: "assistant", text: res.data.answer }]);
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "추천 생성 실패"}`);
    } finally {
      setLoading(false);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    const current = message.trim();
    setMessage("");
    void ask(current);
  }

  function cardLink(card: Card): string | null {
    switch (card.type) {
      case "PROPERTY":
        return card.property_id ? `/properties/${card.property_id}` : null;
      case "TICKET":
        return card.product_id ? `/tickets/${card.product_id}` : null;
      case "PACKAGE":
        return card.package_id ? `/packages/${card.package_id}` : null;
      case "POI":
        return "/nearby";
      default:
        return null;
    }
  }

  return (
    <section className="page">
      <h2>챗봇 추천</h2>
      <form onSubmit={onSubmit} className="chat-form">
        <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={4} />
        <button type="submit" disabled={!canSubmit}>{loading ? "생성 중..." : "질문"}</button>
      </form>
      {error && <p className="error">{error}</p>}
      <ul className="chat-log">
        {messages.map((item, idx) => (
          <li key={`${item.role}-${idx}`} className={`chat-msg ${item.role}`}>
            <strong>{item.role === "user" ? "나" : "추천봇"}:</strong> {item.text}
          </li>
        ))}
      </ul>
      {answer && <p className="chat-answer">{answer}</p>}
      <ul className="card-list">
        {cards.map((card, idx) => {
          const link = cardLink(card);
          return (
            <li key={`${card.type}-${idx}`} className="card">
              <p className="eyebrow">{card.type}</p>
              <h3>{card.title}</h3>
              {card.why && <p>{card.why}</p>}
              {link && <Link to={link}>바로 보기</Link>}
            </li>
          );
        })}
      </ul>
      <div className="chips">
        {followups.map((followup, idx) => (
          <button
            key={`${followup}-${idx}`}
            type="button"
            className="chip-btn"
            onClick={() => {
              setMessage(followup);
              void ask(followup);
            }}
            disabled={loading}
          >
            {followup}
          </button>
        ))}
      </div>
    </section>
  );
}
