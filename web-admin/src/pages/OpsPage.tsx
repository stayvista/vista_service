import { FormEvent, useEffect, useState } from "react";
import { apiDelete, apiGet, apiPatch, apiPost } from "../api/client";

type ApiError = { code?: string; message?: string };
type ReindexData = { scanned: number; upserted: number; failed: number };
type CurationRule = {
  rule_id: number;
  doc_id: string;
  rule_type: string;
  weight: number;
  enabled: boolean;
  updated_at: string;
};
type CurationListData = { items: CurationRule[] };
type DeleteData = { deleted: boolean; rule_id: number };

export function OpsPage() {
  const [limit, setLimit] = useState(10000);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ReindexData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [curationRules, setCurationRules] = useState<CurationRule[]>([]);
  const [docId, setDocId] = useState("");
  const [ruleType, setRuleType] = useState<"BLACKLIST" | "TOP_PICK">("TOP_PICK");
  const [weight, setWeight] = useState(100);
  const [curationError, setCurationError] = useState<string | null>(null);
  const [curationBusy, setCurationBusy] = useState(false);

  useEffect(() => {
    void loadCuration();
  }, []);

  async function loadCuration() {
    try {
      const res = await apiGet<CurationListData>("/v1/admin/chat/curation/rules");
      setCurationRules(res.data.items ?? []);
    } catch (e) {
      const apiError = e as ApiError;
      setCurationError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "큐레이션 규칙 조회 실패"}`);
    }
  }

  async function runReindex(e: FormEvent) {
    e.preventDefault();
    setRunning(true);
    setResult(null);
    setError(null);
    try {
      const query = new URLSearchParams();
      if (limit > 0) query.set("limit", String(limit));
      const res = await apiPost<ReindexData>(
        `/v1/admin/search/reindex?${query.toString()}`,
        {},
      );
      setResult(res.data);
    } catch (e) {
      const apiError = e as ApiError;
      setError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "재색인 요청 실패"}`);
    } finally {
      setRunning(false);
    }
  }

  async function submitCuration(e: FormEvent) {
    e.preventDefault();
    if (!docId.trim()) return;
    setCurationBusy(true);
    setCurationError(null);
    try {
      await apiPost<CurationRule>("/v1/admin/chat/curation/rules", {
        doc_id: docId.trim(),
        rule_type: ruleType,
        weight,
        enabled: true,
      });
      setDocId("");
      await loadCuration();
    } catch (e) {
      const apiError = e as ApiError;
      setCurationError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "규칙 저장 실패"}`);
    } finally {
      setCurationBusy(false);
    }
  }

  async function toggleRule(rule: CurationRule) {
    setCurationBusy(true);
    try {
      await apiPatch<CurationRule>(`/v1/admin/chat/curation/rules/${rule.rule_id}`, {
        enabled: !rule.enabled,
        weight: rule.weight,
      });
      await loadCuration();
    } catch (e) {
      const apiError = e as ApiError;
      setCurationError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "규칙 상태 변경 실패"}`);
    } finally {
      setCurationBusy(false);
    }
  }

  async function removeRule(ruleId: number) {
    setCurationBusy(true);
    try {
      await apiDelete<DeleteData>(`/v1/admin/chat/curation/rules/${ruleId}`);
      await loadCuration();
    } catch (e) {
      const apiError = e as ApiError;
      setCurationError(`${apiError.code ?? "ERROR"}: ${apiError.message ?? "규칙 삭제 실패"}`);
    } finally {
      setCurationBusy(false);
    }
  }

  return (
    <div>
      <h2>운영 도구</h2>
      <form className="row-form" onSubmit={runReindex}>
        <label>
          재색인 limit
          <input
            type="number"
            min={1}
            max={20000}
            value={limit}
            onChange={(e) => setLimit(Math.max(1, Number(e.target.value)))}
          />
        </label>
        <button type="submit" disabled={running}>
          {running ? "요청 중..." : "검색 재색인 실행"}
        </button>
      </form>
      {result && (
        <div>
          <p className="success">재색인 요청 완료</p>
          <p>scanned: {result.scanned}</p>
          <p>upserted: {result.upserted}</p>
          <p>failed: {result.failed}</p>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <p>
        시드 데이터 반영 후 실행 권장:
        <code> ./scripts/seed_local.sh </code>
      </p>

      <hr />

      <h3>추천 큐레이션(Top Picks / Blacklist)</h3>
      <form className="row-form" onSubmit={submitCuration}>
        <label>
          doc_id
          <input
            value={docId}
            onChange={(e) => setDocId(e.target.value)}
            placeholder="예: property:1001 / ticket:20 / poi:99"
          />
        </label>
        <label>
          rule_type
          <select value={ruleType} onChange={(e) => setRuleType(e.target.value as "BLACKLIST" | "TOP_PICK")}>
            <option value="TOP_PICK">TOP_PICK</option>
            <option value="BLACKLIST">BLACKLIST</option>
          </select>
        </label>
        <label>
          weight
          <input
            type="number"
            min={1}
            max={500}
            value={weight}
            onChange={(e) => setWeight(Math.max(1, Math.min(500, Number(e.target.value) || 1)))}
          />
        </label>
        <button type="submit" disabled={curationBusy || !docId.trim()}>
          {curationBusy ? "저장 중..." : "규칙 저장"}
        </button>
      </form>
      {curationError && <p className="error">{curationError}</p>}

      <ul className="table">
        {curationRules.map((rule) => (
          <li key={rule.rule_id}>
            <div>
              <strong>{rule.doc_id}</strong>
              <p>{rule.rule_type} · weight {rule.weight}</p>
            </div>
            <button type="button" onClick={() => toggleRule(rule)} disabled={curationBusy}>
              {rule.enabled ? "비활성화" : "활성화"}
            </button>
            <button type="button" onClick={() => removeRule(rule.rule_id)} disabled={curationBusy}>
              삭제
            </button>
          </li>
        ))}
        {curationRules.length === 0 && <li>등록된 규칙이 없습니다.</li>}
      </ul>
    </div>
  );
}
