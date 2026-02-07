import { FormEvent, useState } from "react";
import { apiPost } from "../api/client";

type Card = { type: string; title: string; property_id?: number; product_id?: number; poi_id?: string };

export function ChatPage() {
  const [message, setMessage] = useState("3박4일 서울 여행 추천해줘");
  const [answer, setAnswer] = useState("");
  const [cards, setCards] = useState<Card[]>([]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const res = await apiPost<{ answer: string; cards: Card[]; followups: string[] }>("/v1/chat/recommend", {
      message,
      context: { city: "Seoul", days: 4, budget_krw: 800000, companions: "COUPLE" },
    });
    setAnswer(res.data.answer);
    setCards(res.data.cards ?? []);
  }

  return (
    <section className="page">
      <h2>챗봇 추천</h2>
      <form onSubmit={onSubmit} className="chat-form">
        <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={4} />
        <button type="submit">질문</button>
      </form>
      {answer && <p>{answer}</p>}
      <ul className="card-list">
        {cards.map((card, idx) => <li key={idx} className="card">{card.type}: {card.title}</li>)}
      </ul>
    </section>
  );
}
