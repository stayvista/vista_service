import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { apiGet, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };

type InquirySummary = {
  inquiry_id: number;
  inquiry_type: string;
  title: string;
  status: string;
  status_label: string;
  created_at: string;
  answered_at?: string | null;
};

type InquiryDetail = {
  inquiry_id: number;
  inquiry_type: string;
  title: string;
  content: string;
  status: string;
  status_label: string;
  answer_content?: string | null;
  created_at: string;
  updated_at: string;
  answered_at?: string | null;
};

type InquiryListResponse = { items: InquirySummary[] };

type InquiryCreateResponse = {
  inquiry_id: number;
  status: string;
  status_label: string;
};

const INQUIRY_TYPES = ["주문/배송", "결제/환불", "쿠폰/혜택", "예약/변경", "기타"];

function formatDateTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("ko-KR", {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    second: "2-digit",
  });
}

function statusTone(status: string): "active" | "sold" | "" {
  if (status === "ANSWERED") return "active";
  if (status === "IN_PROGRESS") return "";
  return "sold";
}

export function SupportPage() {
  const [inquiryType, setInquiryType] = useState(INQUIRY_TYPES[0]);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");

  const [items, setItems] = useState<InquirySummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedInquiryId, setSelectedInquiryId] = useState<number | null>(null);

  const [detail, setDetail] = useState<InquiryDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const detailSectionRef = useRef<HTMLElement | null>(null);

  const canSubmit = useMemo(() => {
    return inquiryType.length > 0 && title.trim().length > 0 && content.trim().length > 0 && !submitLoading;
  }, [content, inquiryType, submitLoading, title]);

  async function loadItems() {
    setLoading(true);
    setError(null);
    try {
      const response = await apiGet<InquiryListResponse>("/v1/me/inquiries?limit=50");
      setItems(response.data.items);
    } catch (e: unknown) {
      const apiError = (typeof e === "object" && e !== null ? e : {}) as ApiError;
      setItems([]);
      setError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "문의 목록을 불러오지 못했습니다."}`);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadItems();
  }, []);

  useEffect(() => {
    if (selectedInquiryId == null) {
      setDetail(null);
      setDetailError(null);
      return;
    }

    setDetailLoading(true);
    setDetailError(null);
    apiGet<InquiryDetail>(`/v1/me/inquiries/${selectedInquiryId}`)
      .then((response) => {
        setDetail(response.data);
      })
      .catch((e: unknown) => {
        const apiError = (typeof e === "object" && e !== null ? e : {}) as ApiError;
        setDetail(null);
        setDetailError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "문의 상세를 불러오지 못했습니다."}`);
      })
      .finally(() => {
        setDetailLoading(false);
      });
  }, [selectedInquiryId]);

  function handleSelectInquiry(inquiryId: number) {
    setSelectedInquiryId(inquiryId);
    window.requestAnimationFrame(() => {
      detailSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) return;

    setSubmitLoading(true);
    setError(null);
    try {
      const response = await apiPost<InquiryCreateResponse>("/v1/me/inquiries", {
        inquiry_type: inquiryType,
        title: title.trim(),
        content: content.trim(),
      });

      setTitle("");
      setContent("");
      await loadItems();
      setSelectedInquiryId(response.data.inquiry_id);
    } catch (e: unknown) {
      const apiError = (typeof e === "object" && e !== null ? e : {}) as ApiError;
      setError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "문의를 접수하지 못했습니다."}`);
    } finally {
      setSubmitLoading(false);
    }
  }

  return (
    <section className="page support-page">
      <article className="support-card">
        <h2>문의 등록</h2>
        <form onSubmit={handleSubmit} className="support-form">
          <label className="field-group">
            문의 유형
            <select value={inquiryType} onChange={(event) => setInquiryType(event.target.value)}>
              {INQUIRY_TYPES.map((type) => (
                <option key={type} value={type}>{type}</option>
              ))}
            </select>
          </label>

          <label className="field-group">
            제목
            <input
              type="text"
              value={title}
              placeholder="문의 제목"
              maxLength={200}
              onChange={(event) => setTitle(event.target.value)}
            />
          </label>

          <label className="field-group">
            내용
            <textarea
              rows={6}
              value={content}
              placeholder="문의 내용을 입력해 주세요"
              maxLength={5000}
              onChange={(event) => setContent(event.target.value)}
            />
          </label>

          <button type="submit" disabled={!canSubmit}>{submitLoading ? "문의 접수 중..." : "문의 접수"}</button>
        </form>
      </article>

      <article className="support-card">
        <h2>문의 내역</h2>
        {loading && <p className="notice info">문의 내역을 불러오는 중입니다...</p>}
        {error && <p className="notice error">{error}</p>}
        {!loading && !error && items.length === 0 && <p className="notice warning">등록된 문의가 없습니다.</p>}

        {items.length > 0 && (
          <ul className="inquiry-list">
            {items.map((item) => {
              const selected = selectedInquiryId === item.inquiry_id;
              const tone = statusTone(item.status);
              return (
                <li key={item.inquiry_id} className={selected ? "inquiry-item selected" : "inquiry-item"}>
                  <div>
                    <h3>{item.title}</h3>
                    <p className="product-copy">
                      {item.inquiry_type} · {formatDateTime(item.created_at)}
                    </p>
                  </div>
                  <div className="inquiry-actions">
                    <span className={tone ? `status-pill ${tone}` : "status-pill"}>{item.status_label}</span>
                    <button
                      type="button"
                      className="outline-btn"
                      onClick={() => handleSelectInquiry(item.inquiry_id)}
                    >
                      상세 보기
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </article>

      <article className="support-card" ref={detailSectionRef}>
        <h2>문의 상세</h2>
        {selectedInquiryId == null && <p className="notice info">문의 내역에서 상세를 선택해 주세요.</p>}
        {detailLoading && <p className="notice info">문의 상세를 불러오는 중입니다...</p>}
        {detailError && <p className="notice error">{detailError}</p>}
        {!detailLoading && !detailError && detail && (
          <div className="support-detail">
            <dl className="mini-meta">
              <div>
                <dt>문의 번호</dt>
                <dd>#{detail.inquiry_id}</dd>
              </div>
              <div>
                <dt>문의 유형</dt>
                <dd>{detail.inquiry_type}</dd>
              </div>
              <div>
                <dt>상태</dt>
                <dd>{detail.status_label}</dd>
              </div>
              <div>
                <dt>등록 시각</dt>
                <dd>{formatDateTime(detail.created_at)}</dd>
              </div>
              <div>
                <dt>답변 시각</dt>
                <dd>{formatDateTime(detail.answered_at)}</dd>
              </div>
            </dl>

            <h3>{detail.title}</h3>
            <p className="support-detail-content">{detail.content}</p>

            <h4>답변</h4>
            <p className="support-detail-answer">{detail.answer_content?.trim() ? detail.answer_content : "아직 등록된 답변이 없습니다."}</p>
          </div>
        )}
      </article>
    </section>
  );
}
