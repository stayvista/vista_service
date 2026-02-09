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
    <section className="page concierge-page">
      <header className="page-head">
        <p className="page-kicker">AI CONCIERGE · PERSONAL ITINERARY</p>
        <div className="page-title-wrap">
          <div>
            <h2>AI 컨시어지</h2>
            <p className="page-summary">
              일정, 예산, 동행 유형을 기반으로 숙소·티켓·주변 스팟을 함께 추천하는 대화형 컨시어지입니다.
            </p>
          </div>
          <div className="page-metrics" aria-label="대화 지표">
            <div>
              <strong>{cards.length}</strong>
              <span>추천 카드</span>
            </div>
            <div>
              <strong>{followups.length}</strong>
              <span>후속 질문</span>
            </div>
          </div>
        </div>
      </header>

      <div className="concierge-layout">
        <form onSubmit={onSubmit} className="concierge-compose">
          <label className="field-group">
            여행 요청
            <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={5} />
          </label>
          <button type="submit" disabled={!canSubmit}>{loading ? "생성 중..." : "추천 요청"}</button>
        </form>

        <div className="concierge-thread">
          <h3>대화 기록</h3>
          {error && <p className="notice error">{error}</p>}
          <ul className="chat-log">
            {messages.map((item, idx) => (
              <li key={`${item.role}-${idx}`} className={`chat-msg ${item.role}`}>
                <strong>{item.role === "user" ? "나" : "추천봇"}</strong>
                <p>{item.text}</p>
              </li>
            ))}
          </ul>
          {!loading && messages.length === 0 && (
            <p className="notice info">예: 3박4일 부산 가족여행 추천해줘</p>
          )}
        </div>
      </div>

      {answer && <p className="chat-answer">{answer}</p>}
      {!loading && !error && messages.length > 0 && cards.length === 0 && (
        <p className="notice warning">추천 카드가 없습니다. 요청 문장을 조금 더 구체적으로 입력해 주세요.</p>
      )}

      <ul className="product-grid">
        {cards.map((card, idx) => {
          const link = cardLink(card);
          return (
            <li key={`${card.type}-${idx}`} className="product-card">
              <div className="product-body">
                <p className="eyebrow">{card.type}</p>
                <h3>{card.title}</h3>
                {card.why && <p className="product-copy">{card.why}</p>}
                {link && <Link to={link} className="inline-cta">바로 보기</Link>}
              </div>
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
