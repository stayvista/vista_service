import { FormEvent, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { apiPost } from "../api/client";
import { getAuthBearerToken } from "../auth/session";

type Card = {
  type: string;
  id?: string;
  title: string;
  price?: string;
  why?: string;
  source?: Array<{ doc_id: string; title: string; snippet: string; source_type: string }>;
  property_id?: number;
  product_id?: number;
  package_id?: number;
  poi_id?: string;
};

type ChatResponse = {
  answer: string;
  assistant_text?: string;
  cards: Card[];
  followups: string[];
  llm_used?: boolean;
  context_used?: Record<string, unknown>;
  sources?: Array<{ doc_id: string; title: string; snippet: string; source_type: string }>;
};

type ChatMessage = {
  role: "user" | "assistant";
  text: string;
};

type ApiError = {
  code?: string;
  message?: string;
};

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:18765";

export function ChatPage() {
  const [message, setMessage] = useState("3박4일 서울 여행 추천해줘");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [answer, setAnswer] = useState<string>("");
  const [streamingAnswer, setStreamingAnswer] = useState("");
  const [cards, setCards] = useState<Card[]>([]);
  const [followups, setFollowups] = useState<string[]>([]);
  const [llmUsed, setLlmUsed] = useState(false);
  const [sources, setSources] = useState<Array<{ doc_id: string; title: string; snippet: string; source_type: string }>>([]);
  const [contextUsed, setContextUsed] = useState<Record<string, unknown>>({});
  const [streamRoute, setStreamRoute] = useState<string>("");
  const [openEvidence, setOpenEvidence] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = useMemo(() => message.trim().length > 0 && !loading, [loading, message]);
  const trustBadge = useMemo(() => {
    let score = 0;
    if (sources.length >= 3) score += 2;
    else if (sources.length > 0) score += 1;
    if (!llmUsed) score += 1;
    if (contextUsed.citation_guard) score -= 2;
    if (contextUsed.prompt_injection_guard) score -= 1;
    if (score >= 3) return { label: "HIGH TRUST", tone: "high" };
    if (score >= 1) return { label: "MEDIUM TRUST", tone: "medium" };
    return { label: "LOW TRUST", tone: "low" };
  }, [contextUsed, llmUsed, sources.length]);

  async function ask(input: string) {
    setLoading(true);
    setError(null);
    setStreamingAnswer("");
    setAnswer("");
    setCards([]);
    setFollowups([]);
    setSources([]);
    setContextUsed({});
    setOpenEvidence({});
    setStreamRoute("");
    setMessages((prev) => [...prev, { role: "user", text: input }]);
    try {
      const payload = {
        message: input,
        context: { city: "Seoul", days: 4, budget_krw: 800000, companions: "COUPLE" },
      };

      let donePayload: ChatResponse | null = null;
      await streamRecommend(payload, (event, data) => {
        if (event === "meta") {
          const route = typeof data?.route === "string" ? data.route : "";
          setStreamRoute(route);
          return;
        }
        if (event === "token") {
          const tokenText = typeof data?.text === "string" ? data.text : "";
          setStreamingAnswer((prev) => `${prev}${tokenText}`);
          return;
        }
        if (event === "done") {
          donePayload = data as ChatResponse;
        }
      });

      if (!donePayload) {
        const fallbackRes = await apiPost<ChatResponse>("/v1/chat/recommend", payload);
        donePayload = fallbackRes.data;
      }

      const finalText = donePayload.assistant_text ?? donePayload.answer;
      setAnswer(finalText);
      setCards(donePayload.cards ?? []);
      setFollowups(donePayload.followups ?? []);
      setLlmUsed(Boolean(donePayload.llm_used));
      setSources(donePayload.sources ?? []);
      setContextUsed(donePayload.context_used ?? {});
      setMessages((prev) => [...prev, { role: "assistant", text: finalText }]);
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "추천 생성 실패"}`);
    } finally {
      setLoading(false);
      setStreamingAnswer("");
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

  function toggleEvidence(cardKey: string) {
    setOpenEvidence((prev) => ({ ...prev, [cardKey]: !prev[cardKey] }));
  }

  async function streamRecommend(
    payload: Record<string, unknown>,
    onEvent: (event: string, data: Record<string, unknown>) => void,
  ) {
    const token = getAuthBearerToken();
    const response = await fetch(`${API_BASE}/v1/chat/recommend:stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      try {
        const parsed = (await response.json()) as { error?: ApiError };
        throw parsed.error ?? { code: "ERROR", message: `HTTP ${response.status}` };
      } catch {
        throw { code: "ERROR", message: `HTTP ${response.status}` } as ApiError;
      }
    }
    if (!response.body) {
      throw { code: "STREAM_UNAVAILABLE", message: "스트리밍 응답 본문이 없습니다." } as ApiError;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });

      let separatorIdx = buffer.indexOf("\n\n");
      while (separatorIdx >= 0) {
        const rawEvent = buffer.slice(0, separatorIdx).trim();
        buffer = buffer.slice(separatorIdx + 2);

        if (rawEvent.length > 0) {
          let event = "message";
          const dataLines: string[] = [];
          rawEvent.split(/\r?\n/).forEach((line) => {
            if (line.startsWith("event:")) {
              event = line.slice("event:".length).trim();
            } else if (line.startsWith("data:")) {
              dataLines.push(line.slice("data:".length).trim());
            }
          });

          if (dataLines.length > 0) {
            const joined = dataLines.join("\n");
            try {
              onEvent(event, JSON.parse(joined));
            } catch {
              onEvent(event, { text: joined });
            }
          }
        }
        separatorIdx = buffer.indexOf("\n\n");
      }
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
            <div>
              <strong>{llmUsed ? "LLM" : "RAG"}</strong>
              <span>응답 경로</span>
            </div>
            <div>
              <strong>{sources.length}</strong>
              <span>추천 근거</span>
            </div>
            <div className={`trust-badge ${trustBadge.tone}`}>
              <strong>{trustBadge.label}</strong>
              <span>{streamRoute || String(contextUsed.route ?? "-")}</span>
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
            {loading && streamingAnswer && (
              <li className="chat-msg assistant">
                <strong>추천봇</strong>
                <p>{streamingAnswer}</p>
              </li>
            )}
          </ul>
          {!loading && messages.length === 0 && (
            <p className="notice info">예: 3박4일 부산 가족여행 추천해줘</p>
          )}
        </div>
      </div>

      {answer && <p className="chat-answer">{answer}</p>}
      {sources.length > 0 && (
        <ul className="card-list">
          {sources.slice(0, 3).map((source) => (
            <li key={source.doc_id} className="card">
              <p className="eyebrow">{source.source_type}</p>
              <h3>{source.title}</h3>
              <p className="product-copy">{source.snippet}</p>
            </li>
          ))}
        </ul>
      )}
      {!loading && !error && messages.length > 0 && cards.length === 0 && (
        <p className="notice warning">추천 카드가 없습니다. 요청 문장을 조금 더 구체적으로 입력해 주세요.</p>
      )}

      <ul className="product-grid">
        {cards.map((card, idx) => {
          const link = cardLink(card);
          const key = `${card.type}-${card.id ?? idx}`;
          const evidence = card.source ?? [];
          return (
            <li key={key} className="product-card">
              <div className="product-body">
                <p className="eyebrow">{card.type}</p>
                <h3>{card.title}</h3>
                {card.why && <p className="product-copy">{card.why}</p>}
                {link && <Link to={link} className="inline-cta">바로 보기</Link>}
                <button
                  type="button"
                  className="inline-ghost"
                  onClick={() => toggleEvidence(key)}
                >
                  {openEvidence[key] ? "근거 숨기기" : "근거 보기"}
                </button>
                {openEvidence[key] && (
                  <div className="evidence-panel">
                    {evidence.length > 0 ? (
                      <ul>
                        {evidence.map((source) => (
                          <li key={`${source.doc_id}-${source.source_type}`}>
                            <strong>{source.title}</strong>
                            <p>{source.snippet}</p>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="notice info">노출 가능한 근거가 없습니다.</p>
                    )}
                  </div>
                )}
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
