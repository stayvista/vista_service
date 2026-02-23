import { FormEvent, KeyboardEvent as ReactKeyboardEvent, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getAuthBearerToken, getAuthUser } from "../../auth/session";
import { StaySearchInput } from "../search/searchTypes";

type ChatCard = {
  type: string;
  id?: string;
  title: string;
  price?: string;
  why?: string;
  property_id?: number;
  product_id?: number;
  package_id?: number;
};

type ChatData = {
  answer: string;
  assistant_text?: string;
  cards: ChatCard[];
  followups: string[];
  llm_used?: boolean;
  context_used?: Record<string, unknown>;
};

export type SearchHandoffFilter = {
  key: string;
  value: string;
  label: string;
  reason?: string;
};

export type SearchHandoffSearchPatch = {
  city?: string;
  days?: number;
  companions?: string;
};

type SearchHandoffSortHint = {
  value: string;
  label: string;
  reason?: string;
};

type SearchHandoffSourceHint = {
  sourceType: string;
  label: string;
  reason?: string;
  prompt: string;
};

type SearchHandoffClarifyAction = {
  slot: string;
  label: string;
  prompt: string;
  searchPatch?: SearchHandoffSearchPatch;
  recommendedSourceTypes?: string[];
};

export type SearchHandoffPayload = {
  filters: SearchHandoffFilter[];
  summary?: string;
  confidence?: number;
  profileApplied?: boolean;
  rationale?: string[];
  sortHint?: SearchHandoffSortHint | null;
  sourceHints?: SearchHandoffSourceHint[];
  clarifyQuestions?: string[];
  clarifyRequired?: boolean;
  missingSlots?: string[];
  searchPatch?: SearchHandoffSearchPatch;
  recommendedSourceTypes?: string[];
};

type ChatMessage = {
  role: "user" | "assistant";
  text: string;
};

type ApiError = {
  code?: string;
  message?: string;
};

type Props = {
  searchContext: StaySearchInput;
  onSearch: (next: StaySearchInput, handoff?: SearchHandoffPayload) => void;
};

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:18765";
const CONCIERGE_SESSION_KEY = "stayvista.web_user.concierge.session_id";
const CONCIERGE_DOCK_STATE_KEY = "stayvista.web_user.concierge.dock_state.v1";
type TelemetryEventName =
  | "ai_widget_open"
  | "ai_widget_prompt_submit"
  | "ai_widget_followup_click"
  | "ai_widget_clarify_click"
  | "ai_widget_clarify_action_click"
  | "ai_widget_sort_hint_click"
  | "ai_widget_scope_hint_click"
  | "ai_widget_filter_apply"
  | "ai_widget_search_handoff"
  | "ai_widget_view_results";

type ConciergeDockState = {
  open: boolean;
  activeSourceTypes: string[];
  messageDraft: string;
  messages: ChatMessage[];
  answer: string;
  cards: ChatCard[];
  followups: string[];
  routeLabel: string;
  handoffSummary: string;
  handoffConfidence: number | null;
  handoffProfileApplied: boolean;
  handoffRationale: string[];
  handoffSortHint: SearchHandoffSortHint | null;
  handoffSourceHints: SearchHandoffSourceHint[];
  handoffClarifyQuestions: string[];
  handoffClarifyActions: SearchHandoffClarifyAction[];
  handoffClarifyRequired: boolean;
  handoffMissingSlots: string[];
  handoffSearchPatch: SearchHandoffSearchPatch;
  handoffRecommendedSourceTypes: string[];
  handoffFilters: SearchHandoffFilter[];
  selectedHandoffFilterKeys: string[];
};

const SOURCE_TYPE_FILTERS: Record<string, readonly string[]> = {
  stay: ["PROPERTY"],
  city: ["PROPERTY", "POI"],
  food: ["POI"],
  package: ["PACKAGE", "PROPERTY"],
  ticket: ["TICKET", "POI"],
};

const CITY_CODE_ALIAS: Record<string, string> = {
  seoul: "Seoul",
  서울: "Seoul",
  busan: "Busan",
  부산: "Busan",
  jeju: "Jeju",
  제주: "Jeju",
  incheon: "Incheon",
  인천: "Incheon",
};

const CITY_CODE_LABEL: Record<string, string> = {
  Seoul: "서울",
  Busan: "부산",
  Jeju: "제주",
  Incheon: "인천",
};

const VALID_SOURCE_TYPES = new Set(["PROPERTY", "TICKET", "PACKAGE", "POI"]);
const SOURCE_TYPE_LABELS: Record<string, string> = {
  PROPERTY: "숙소",
  PACKAGE: "패키지",
  TICKET: "티켓",
  POI: "주변 추천",
};
const SORT_VALUE_LABELS: Record<string, string> = {
  best_match: "Best match",
  price_asc: "가격 낮은순",
  price_desc: "가격 높은순",
  rating_desc: "평점 높은순",
  distance: "거리순",
};
const ALLOWED_SORT_VALUES = new Set(Object.keys(SORT_VALUE_LABELS));
const ALLOWED_HINT_SOURCE_TYPES = new Set(["PROPERTY", "PACKAGE", "TICKET", "POI"]);
const MISSING_SLOT_LABELS: Record<string, string> = {
  city: "도시",
  days: "일정",
  companions: "동행",
  budget: "예산",
  preferences: "선호 옵션",
};

export function ConciergeDock({ searchContext, onSearch }: Props) {
  const navigate = useNavigate();
  const restoredState = useMemo(() => loadConciergeDockState(), []);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const composerRef = useRef<HTMLTextAreaElement | null>(null);
  const [open, setOpen] = useState(restoredState.open);
  const [isCompactViewport, setIsCompactViewport] = useState(
    () => typeof window !== "undefined" && window.innerWidth <= 1080,
  );
  const [activeSourceTypes, setActiveSourceTypes] = useState<string[]>(
    restoredState.activeSourceTypes.length > 0 ? restoredState.activeSourceTypes : [...SOURCE_TYPE_FILTERS.city],
  );
  const [message, setMessage] = useState(restoredState.messageDraft);
  const [messages, setMessages] = useState<ChatMessage[]>(restoredState.messages);
  const [answer, setAnswer] = useState(restoredState.answer);
  const [streamingAnswer, setStreamingAnswer] = useState("");
  const [cards, setCards] = useState<ChatCard[]>(restoredState.cards);
  const [followups, setFollowups] = useState<string[]>(restoredState.followups);
  const [routeLabel, setRouteLabel] = useState<string>(restoredState.routeLabel || "-");
  const [handoffSummary, setHandoffSummary] = useState(restoredState.handoffSummary);
  const [handoffConfidence, setHandoffConfidence] = useState<number | null>(restoredState.handoffConfidence);
  const [handoffProfileApplied, setHandoffProfileApplied] = useState(restoredState.handoffProfileApplied);
  const [handoffRationale, setHandoffRationale] = useState<string[]>(restoredState.handoffRationale);
  const [handoffSortHint, setHandoffSortHint] = useState<SearchHandoffSortHint | null>(restoredState.handoffSortHint);
  const [handoffSourceHints, setHandoffSourceHints] = useState<SearchHandoffSourceHint[]>(restoredState.handoffSourceHints);
  const [handoffClarifyQuestions, setHandoffClarifyQuestions] = useState<string[]>(restoredState.handoffClarifyQuestions);
  const [handoffClarifyActions, setHandoffClarifyActions] = useState<SearchHandoffClarifyAction[]>(restoredState.handoffClarifyActions);
  const [handoffClarifyRequired, setHandoffClarifyRequired] = useState(restoredState.handoffClarifyRequired);
  const [handoffMissingSlots, setHandoffMissingSlots] = useState<string[]>(restoredState.handoffMissingSlots);
  const [handoffSearchPatch, setHandoffSearchPatch] = useState<SearchHandoffSearchPatch>(restoredState.handoffSearchPatch);
  const [handoffRecommendedSourceTypes, setHandoffRecommendedSourceTypes] = useState<string[]>(restoredState.handoffRecommendedSourceTypes);
  const [handoffFilters, setHandoffFilters] = useState<SearchHandoffFilter[]>(restoredState.handoffFilters);
  const [selectedHandoffFilterKeys, setSelectedHandoffFilterKeys] = useState<string[]>(restoredState.selectedHandoffFilterKeys);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = useMemo(() => message.trim().length > 0 && !loading, [loading, message]);
  const cityLabel = (searchContext.placeLabel || searchContext.city || "서울").trim();
  const nights = useMemo(() => getNightCount(searchContext.checkIn, searchContext.checkOut), [searchContext.checkIn, searchContext.checkOut]);
  const quickPrompts = useMemo(() => [
    {
      label: "숙소 추천",
      prompt: `${cityLabel}에서 바로 예약 가능한 숙소 3곳 추천해줘`,
      sourceType: SOURCE_TYPE_FILTERS.stay,
    },
    {
      label: "가족 여행",
      prompt: `${cityLabel} 가족 여행 기준으로 객실 넓고 조식 좋은 숙소 추천해줘`,
      sourceType: SOURCE_TYPE_FILTERS.stay,
    },
    {
      label: "맛집/명소",
      prompt: `${cityLabel} 맛집과 관광지 위주로 동선 추천해줘`,
      sourceType: SOURCE_TYPE_FILTERS.food,
    },
    {
      label: `${nights}박 일정`,
      prompt: `${cityLabel} ${nights}박 ${nights + 1}일 여행 일정 추천해줘`,
      sourceType: SOURCE_TYPE_FILTERS.city,
    },
    {
      label: "패키지/티켓",
      prompt: `${cityLabel}에서 이용 가능한 패키지나 티켓 추천해줘`,
      sourceType: SOURCE_TYPE_FILTERS.package,
    },
  ], [cityLabel, nights]);
  const selectedHandoffFilters = useMemo(() => {
    const selected = new Set(selectedHandoffFilterKeys);
    return handoffFilters.filter((item) => selected.has(filterToken(item)));
  }, [handoffFilters, selectedHandoffFilterKeys]);
  const selectedSortValue = useMemo(
    () => selectedHandoffFilters.find((item) => item.key === "sort")?.value ?? null,
    [selectedHandoffFilters],
  );
  const sortHintCandidates = useMemo<SearchHandoffFilter[]>(() => {
    const options = new Map<string, SearchHandoffFilter>();
    handoffFilters
      .filter((item) => item.key === "sort" && ALLOWED_SORT_VALUES.has(item.value))
      .forEach((item) => options.set(item.value, item));
    if (handoffSortHint && ALLOWED_SORT_VALUES.has(handoffSortHint.value)) {
      options.set(handoffSortHint.value, {
        key: "sort",
        value: handoffSortHint.value,
        label: handoffSortHint.label || SORT_VALUE_LABELS[handoffSortHint.value] || handoffSortHint.value,
        reason: handoffSortHint.reason,
      });
    }
    return Array.from(options.values()).slice(0, 3);
  }, [handoffFilters, handoffSortHint]);

  useEffect(() => {
    if (typeof window === "undefined") {
      return undefined;
    }
    const onResize = () => {
      setIsCompactViewport(window.innerWidth <= 1080);
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const timer = window.setTimeout(() => {
      composerRef.current?.focus();
      const node = threadRef.current;
      if (node) {
        node.scrollTop = node.scrollHeight;
      }
    }, 90);
    return () => window.clearTimeout(timer);
  }, [open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const node = threadRef.current;
    if (!node) {
      return;
    }
    node.scrollTop = node.scrollHeight;
  }, [open, messages, streamingAnswer, answer, loading, cards.length, followups.length]);

  useEffect(() => {
    saveConciergeDockState({
      open,
      activeSourceTypes,
      messageDraft: message,
      messages: messages.slice(-20),
      answer,
      cards: cards.slice(0, 6),
      followups: followups.slice(0, 6),
      routeLabel,
      handoffSummary,
      handoffConfidence,
      handoffProfileApplied,
      handoffRationale: handoffRationale.slice(0, 6),
      handoffSortHint,
      handoffSourceHints: handoffSourceHints.slice(0, 6),
      handoffClarifyQuestions: handoffClarifyQuestions.slice(0, 6),
      handoffClarifyActions: handoffClarifyActions.slice(0, 8),
      handoffClarifyRequired,
      handoffMissingSlots: handoffMissingSlots.slice(0, 6),
      handoffSearchPatch,
      handoffRecommendedSourceTypes,
      handoffFilters: handoffFilters.slice(0, 8),
      selectedHandoffFilterKeys: selectedHandoffFilterKeys.slice(0, 8),
    });
  }, [
    open,
    activeSourceTypes,
    message,
    messages,
    answer,
    cards,
    followups,
    routeLabel,
    handoffSummary,
    handoffConfidence,
    handoffProfileApplied,
    handoffRationale,
    handoffSortHint,
    handoffSourceHints,
    handoffClarifyQuestions,
    handoffClarifyActions,
    handoffClarifyRequired,
    handoffMissingSlots,
    handoffSearchPatch,
    handoffRecommendedSourceTypes,
    handoffFilters,
    selectedHandoffFilterKeys,
  ]);

  function resetConversation() {
    setMessage("");
    setMessages([]);
    setAnswer("");
    setStreamingAnswer("");
    setCards([]);
    setFollowups([]);
    setRouteLabel("-");
    setHandoffSummary("");
    setHandoffConfidence(null);
    setHandoffProfileApplied(false);
    setHandoffRationale([]);
    setHandoffSortHint(null);
    setHandoffSourceHints([]);
    setHandoffClarifyQuestions([]);
    setHandoffClarifyActions([]);
    setHandoffClarifyRequired(false);
    setHandoffMissingSlots([]);
    setHandoffSearchPatch({});
    setHandoffRecommendedSourceTypes([]);
    setHandoffFilters([]);
    setSelectedHandoffFilterKeys([]);
    setError(null);
    setLoading(false);
    setActiveSourceTypes([...SOURCE_TYPE_FILTERS.city]);
  }

  async function ask(
    input: string,
    sourceTypes: readonly string[] = activeSourceTypes,
    searchContextOverride?: StaySearchInput,
  ) {
    const normalizedSourceTypes = normalizeSourceTypes(sourceTypes);
    setActiveSourceTypes(normalizedSourceTypes);
    setLoading(true);
    setError(null);
    setAnswer("");
    setStreamingAnswer("");
    setCards([]);
    setFollowups([]);
    setHandoffSummary("");
    setHandoffConfidence(null);
    setHandoffProfileApplied(false);
    setHandoffRationale([]);
    setHandoffSortHint(null);
    setHandoffSourceHints([]);
    setHandoffClarifyQuestions([]);
    setHandoffClarifyActions([]);
    setHandoffClarifyRequired(false);
    setHandoffMissingSlots([]);
    setHandoffSearchPatch({});
    setHandoffRecommendedSourceTypes([]);
    setHandoffFilters([]);
    setSelectedHandoffFilterKeys([]);
    setMessages((prev) => [...prev, { role: "user", text: input }]);

    const payload = {
      message: input,
      context: buildChatContext(searchContextOverride ?? searchContext, normalizedSourceTypes),
    };

    try {
      let donePayload: ChatData | null = null;
      await streamRecommend(payload, (event, data) => {
        if (event === "meta") {
          const route = typeof data?.route === "string" ? data.route : "";
          setRouteLabel(route || "-");
          return;
        }
        if (event === "token") {
          const tokenText = typeof data?.text === "string" ? data.text : "";
          setStreamingAnswer((prev) => `${prev}${tokenText}`);
          return;
        }
        if (event === "done") {
          donePayload = data as ChatData;
        }
      });

      if (!donePayload) {
        donePayload = await recommend(payload);
      }

      const finalAnswer = donePayload.assistant_text ?? donePayload.answer;
      const handoff = extractSearchHandoff(donePayload.context_used);
      setAnswer(finalAnswer);
      setCards(donePayload.cards ?? []);
      setFollowups(donePayload.followups ?? []);
      setHandoffSummary(handoff.summary);
      setHandoffConfidence(handoff.confidence);
      setHandoffProfileApplied(handoff.profileApplied);
      setHandoffRationale(handoff.rationale);
      setHandoffSortHint(handoff.sortHint);
      setHandoffSourceHints(handoff.sourceHints);
      setHandoffClarifyQuestions(handoff.clarifyQuestions);
      setHandoffClarifyActions(handoff.clarifyActions);
      setHandoffClarifyRequired(handoff.clarifyRequired);
      setHandoffMissingSlots(handoff.missingSlots);
      setHandoffSearchPatch(handoff.searchPatch);
      setHandoffRecommendedSourceTypes(handoff.recommendedSourceTypes);
      setHandoffFilters(handoff.filters);
      setSelectedHandoffFilterKeys(handoff.filters.map((item) => filterToken(item)));
      if (handoff.recommendedSourceTypes.length > 0) {
        setActiveSourceTypes(handoff.recommendedSourceTypes);
      }
      setMessages((prev) => [...prev, { role: "assistant", text: finalAnswer }]);
      setRouteLabel(String(donePayload.context_used?.route ?? "-"));
    } catch (e) {
      const err = e as ApiError;
      setError(`${err.code ?? "ERROR"}: ${err.message ?? "AI 추천 생성 실패"}`);
    } finally {
      setLoading(false);
      setStreamingAnswer("");
    }
  }

  function submitPrompt() {
    if (!canSubmit) {
      return;
    }
    const current = message.trim();
    const inferredSourceTypes = inferSourceTypesFromPrompt(current);
    const nextSourceTypes = inferredSourceTypes.length > 0 ? inferredSourceTypes : activeSourceTypes;
    if (inferredSourceTypes.length > 0) {
      setActiveSourceTypes(nextSourceTypes);
    }
    setMessage("");
    const scope = sourceTypeScope(nextSourceTypes);
    track("ai_widget_prompt_submit", "text_input", { source_type_scope: scope });
    void ask(current, nextSourceTypes);
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    submitPrompt();
  }

  function onComposerKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey) {
      return;
    }
    const native = event.nativeEvent as globalThis.KeyboardEvent;
    if (native.isComposing || native.keyCode === 229) {
      return;
    }
    event.preventDefault();
    submitPrompt();
  }

  function launchSearch() {
    if (selectedHandoffFilters.length > 0) {
      track("ai_widget_filter_apply", "search_cta");
    }
    const handoffSourceTypes = handoffRecommendedSourceTypes.length > 0
      ? handoffRecommendedSourceTypes
      : activeSourceTypes;
    track("ai_widget_search_handoff", "search_cta", {
      filter_count: selectedHandoffFilters.length,
      handoff_confidence: handoffConfidence ?? undefined,
      handoff_profile_applied: handoffProfileApplied,
      clarify_required: handoffClarifyRequired,
      missing_slot_count: handoffMissingSlots.length,
      source_type_scope: sourceTypeScope(handoffSourceTypes),
    });
    const nextSearchContext = applySearchPatch(searchContext, handoffSearchPatch);
    const payload = selectedHandoffFilters.length > 0
      ? {
          filters: selectedHandoffFilters,
          summary: handoffSummary || undefined,
          confidence: handoffConfidence ?? undefined,
          profileApplied: handoffProfileApplied,
          rationale: handoffRationale,
          sortHint: handoffSortHint ?? undefined,
          clarifyQuestions: handoffClarifyQuestions,
          clarifyRequired: handoffClarifyRequired,
          missingSlots: handoffMissingSlots,
          searchPatch: handoffSearchPatch,
          recommendedSourceTypes: handoffSourceTypes,
        }
      : undefined;
    onSearch(nextSearchContext, payload);
    if (isCompactViewport) {
      setOpen(false);
    }
  }

  function viewAllCards() {
    track("ai_widget_view_results", "results_cta");
    const typeSet = new Set(cards.map((item) => item.type.toUpperCase()));
    if (typeSet.has("PROPERTY")) {
      launchSearch();
      return;
    }
    if (typeSet.has("PACKAGE")) {
      navigate(`/packages?city=${encodeURIComponent(searchContext.city ?? "Seoul")}`);
      return;
    }
    if (typeSet.has("TICKET")) {
      navigate(`/tickets?q=${encodeURIComponent(cityLabel)}`);
      return;
    }
    navigate("/nearby");
  }

  function track(
    eventName: TelemetryEventName,
    source: string,
    extra?: {
      filter_count?: number;
      handoff_confidence?: number;
      handoff_profile_applied?: boolean;
      clarify_required?: boolean;
      missing_slot_count?: number;
      source_type_scope?: string;
      clarify_slot?: string;
      sort_value?: string;
      target_source_type?: string;
    },
  ) {
    const payload = {
      event_name: eventName,
      source,
      route: routeLabel,
      ...extra,
    };
    void sendWidgetTelemetry(payload);
  }

  function toggleHandoffFilter(filter: SearchHandoffFilter) {
    const token = filterToken(filter);
    setSelectedHandoffFilterKeys((prev) => (
      filter.key === "sort"
        ? (() => {
            const withoutSort = prev.filter((item) => !item.startsWith("sort:"));
            return prev.includes(token) ? withoutSort : [...withoutSort, token];
          })()
        : (prev.includes(token) ? prev.filter((item) => item !== token) : [...prev, token])
    ));
    track("ai_widget_filter_apply", "filter_chip");
  }

  function applySortHint(filter: SearchHandoffFilter) {
    if (filter.key !== "sort") {
      return;
    }
    setHandoffFilters((prev) => {
      const withoutSort = prev.filter((item) => item.key !== "sort");
      return [...withoutSort, filter];
    });
    setSelectedHandoffFilterKeys((prev) => {
      const token = filterToken(filter);
      const withoutSort = prev.filter((item) => !item.startsWith("sort:"));
      return [...withoutSort, token];
    });
    track("ai_widget_sort_hint_click", "filter_chip", {
      sort_value: filter.value,
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
  }

  return (
    <>
      <button
        type="button"
        className={open ? "concierge-fab active" : "concierge-fab"}
        onClick={() => {
          setOpen((prev) => {
            const next = !prev;
            if (next) {
              track("ai_widget_open", "fab");
            }
            return next;
          });
        }}
        aria-label={open ? "AI 도우미 닫기" : "AI 도우미 열기"}
      >
        <span className="concierge-fab-robot" aria-hidden="true">🤖</span>
        <span className="sr-only">{open ? "AI 도우미 닫기" : "AI 도우미 열기"}</span>
      </button>
      {open && isCompactViewport && (
        <button
          type="button"
          className="concierge-backdrop"
          onClick={() => setOpen(false)}
          aria-label="AI 도우미 닫기"
        />
      )}
      <aside className={open ? "concierge-dock open" : "concierge-dock"} aria-label="AI 여행 도우미">
        <div className="concierge-dock-head">
          <div>
            <p className="concierge-kicker">LIVE BOOKING COPILOT</p>
            <h2>AI 여행 도우미</h2>
            <p className="concierge-summary">현재 검색 조건을 바탕으로 숙소/명소/패키지를 바로 추천합니다.</p>
          </div>
          <div className="concierge-head-actions">
            <button
              type="button"
              className="chip-btn concierge-reset"
              disabled={loading}
              onClick={resetConversation}
            >
              새 대화
            </button>
            <button type="button" className="chip-btn concierge-close" onClick={() => setOpen(false)}>닫기</button>
          </div>
        </div>

        <div className="concierge-context">
          <span>{cityLabel}</span>
          <span>{searchContext.checkIn} ~ {searchContext.checkOut}</span>
          <span>객실 {searchContext.guests.rooms} · 성인 {searchContext.guests.adults} · 아동 {searchContext.guests.children}</span>
        </div>

        <div className="concierge-quick-prompts">
          {quickPrompts.map((item) => (
            <button
              key={item.label}
              type="button"
              className="chip-btn"
              disabled={loading}
                onClick={() => {
                  const scope = sourceTypeScope(item.sourceType);
                  track("ai_widget_prompt_submit", "quick_prompt", { source_type_scope: scope });
                  void ask(item.prompt, item.sourceType);
                }}
              >
                {item.label}
            </button>
          ))}
        </div>

        {handoffFilters.length > 0 && (
          <section className="concierge-handoff-panel">
            <div className="concierge-handoff-head">
              <p className="concierge-handoff-title">AI 추천 필터</p>
              <div className="concierge-handoff-badges">
                {handoffConfidence !== null && (
                  <span className="concierge-handoff-confidence">신뢰도 {Math.round(handoffConfidence * 100)}%</span>
                )}
                {handoffProfileApplied && (
                  <span className="concierge-handoff-profile">개인화 반영</span>
                )}
              </div>
            </div>
            <p className="concierge-handoff-summary">{handoffSummary || "추천 검색 조건을 선택해 적용하세요."}</p>
            {handoffRecommendedSourceTypes.length > 0 && (
              <p className="concierge-handoff-scope">
                추천 범위: {sourceTypeScopeLabel(handoffRecommendedSourceTypes)}
              </p>
            )}
            {handoffClarifyRequired && handoffMissingSlots.length > 0 && (
              <p className="concierge-handoff-missing">
                추가 확인 필요: {describeMissingSlots(handoffMissingSlots)}
              </p>
            )}
            {describeSearchPatch(handoffSearchPatch) && (
              <p className="concierge-handoff-patch">
                적용 예정: {describeSearchPatch(handoffSearchPatch)}
              </p>
            )}
            {handoffRationale.length > 0 && (
              <ul className="concierge-handoff-rationale">
                {handoffRationale.slice(0, 3).map((item, index) => (
                  <li key={`${item}-${index}`}>{item}</li>
                ))}
              </ul>
            )}
            {sortHintCandidates.length > 0 && (
              <div className="concierge-handoff-sort">
                <p className="concierge-handoff-sort-title">추천 정렬</p>
                <div className="concierge-handoff-sort-options">
                  {sortHintCandidates.map((filter) => {
                    const active = selectedSortValue === filter.value;
                    return (
                      <button
                        key={`sort-hint-${filter.value}`}
                        type="button"
                        className={active ? "chip-btn active" : "chip-btn"}
                        disabled={loading}
                        title={filter.reason ?? filter.label}
                        onClick={() => applySortHint(filter)}
                      >
                        {filter.label}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}
            {handoffClarifyQuestions.length > 0 && (
              <div className="concierge-handoff-clarify">
                {handoffClarifyQuestions.slice(0, 3).map((question, index) => (
                  <button
                    key={`${question}-${index}`}
                    type="button"
                    className="chip-btn"
                    disabled={loading}
                    onClick={() => {
                      const scope = sourceTypeScope(activeSourceTypes);
                      track("ai_widget_clarify_click", "handoff_clarify", { source_type_scope: scope });
                      void ask(question, activeSourceTypes);
                    }}
                  >
                    {question}
                  </button>
                ))}
              </div>
            )}
            {handoffClarifyActions.length > 0 && (
              <div className="concierge-handoff-actions">
                {handoffClarifyActions.map((action, index) => (
                  <button
                    key={`${action.slot}:${action.label}:${index}`}
                    type="button"
                    className="chip-btn"
                    disabled={loading}
                    onClick={() => {
                      const nextPatch = mergeSearchPatch(handoffSearchPatch, action.searchPatch ?? {});
                      const patchedContext = applySearchPatch(searchContext, nextPatch);
                      const sourceTypes = action.recommendedSourceTypes && action.recommendedSourceTypes.length > 0
                        ? action.recommendedSourceTypes
                        : activeSourceTypes;
                      setHandoffSearchPatch(nextPatch);
                      track("ai_widget_clarify_action_click", "handoff_clarify", {
                        clarify_slot: action.slot,
                        source_type_scope: sourceTypeScope(sourceTypes),
                      });
                      void ask(action.prompt, sourceTypes, patchedContext);
                    }}
                  >
                    {action.label}
                  </button>
                ))}
              </div>
            )}
            {handoffSourceHints.length > 0 && (
              <div className="concierge-handoff-source">
                <p className="concierge-handoff-sort-title">추천 범위 전환</p>
                <div className="concierge-handoff-sort-options">
                  {handoffSourceHints.map((hint, index) => (
                    <button
                      key={`${hint.sourceType}:${hint.label}:${index}`}
                      type="button"
                      className="chip-btn"
                      disabled={loading}
                      title={hint.reason ?? hint.label}
                      onClick={() => {
                        const nextSourceTypes = normalizeSourceTypes([hint.sourceType]);
                        setActiveSourceTypes(nextSourceTypes);
                        track("ai_widget_scope_hint_click", "scope_hint", {
                          source_type_scope: sourceTypeScope(nextSourceTypes),
                          target_source_type: hint.sourceType,
                        });
                        void ask(hint.prompt, nextSourceTypes);
                      }}
                    >
                      {hint.label}
                    </button>
                  ))}
                </div>
              </div>
            )}
            <div className="concierge-handoff-filters">
              {handoffFilters.map((filter) => {
                const token = filterToken(filter);
                const active = selectedHandoffFilterKeys.includes(token);
                return (
                  <button
                    key={token}
                    type="button"
                    className={active ? "chip-btn active" : "chip-btn"}
                    onClick={() => toggleHandoffFilter(filter)}
                    title={filter.reason ?? filter.label}
                  >
                    {filter.label}
                  </button>
                );
              })}
            </div>
            <p className="concierge-handoff-count">
              적용 예정 필터 {selectedHandoffFilters.length}개
            </p>
          </section>
        )}

        <form className="concierge-dock-form" onSubmit={submit}>
          <textarea
            ref={composerRef}
            value={message}
            onChange={(event) => setMessage(event.target.value)}
            onKeyDown={onComposerKeyDown}
            rows={3}
            placeholder="예) 서울 3박4일, 가족여행, 조식/주차 필수 숙소 추천해줘"
          />
          <p className="concierge-compose-hint">Enter 전송 · Shift+Enter 줄바꿈</p>
          <button type="submit" disabled={!canSubmit}>{loading ? "생성 중..." : "추천 요청"}</button>
        </form>

        {error && <p className="notice warning">{error}</p>}

        <div ref={threadRef} className="concierge-dock-thread">
          {!loading && messages.length === 0 && (
            <p className="concierge-thread-empty">
              AI에게 조건을 말하면 추천 결과와 검색 필터를 바로 만들어 드립니다.
            </p>
          )}
          {messages.slice(-6).map((item, index) => (
            <div key={`${item.role}-${index}`} className={item.role === "assistant" ? "dock-msg assistant" : "dock-msg user"}>
              <strong>{item.role === "assistant" ? "도우미" : "나"}</strong>
              <p>{item.text}</p>
            </div>
          ))}
          {loading && streamingAnswer && (
            <div className="dock-msg assistant">
              <strong>도우미</strong>
              <p>{streamingAnswer}</p>
            </div>
          )}
          {!loading && answer && (
            <p className="concierge-final-answer">
              <strong>요약</strong>
              <span>{answer}</span>
            </p>
          )}
        </div>

        {cards.length > 0 && (
          <ul className="concierge-card-list">
            {cards.slice(0, 4).map((card, index) => (
              <li key={`${card.type}-${card.id ?? index}`}>
                <p className="eyebrow">{card.type}</p>
                <h3>{card.title}</h3>
                {card.why && <p>{card.why}</p>}
                <div className="concierge-card-actions">
                  {renderCardLink(card)}
                </div>
              </li>
            ))}
          </ul>
        )}

        {followups.length > 0 && (
          <div className="concierge-followups">
            {followups.map((followup, index) => (
              <button
                key={`${followup}-${index}`}
                type="button"
                className="chip-btn"
                disabled={loading}
                onClick={() => {
                  const scope = sourceTypeScope(activeSourceTypes);
                  track("ai_widget_followup_click", "followup", { source_type_scope: scope });
                  void ask(followup, activeSourceTypes);
                }}
              >
                {followup}
              </button>
            ))}
          </div>
        )}

        <div className="concierge-cta-row">
          <button type="button" className="chip-btn" onClick={launchSearch}>조건으로 숙소 검색</button>
          <button type="button" className="chip-btn" onClick={viewAllCards} disabled={cards.length === 0}>
            추천 결과 보기
          </button>
          <span className="concierge-route">route: {routeLabel}</span>
        </div>
      </aside>
    </>
  );
}

function renderCardLink(card: ChatCard) {
  switch (card.type.toUpperCase()) {
    case "PROPERTY":
      return card.property_id ? <Link className="inline-cta" to={`/properties/${card.property_id}`}>상세 보기</Link> : null;
    case "TICKET":
      return card.product_id ? <Link className="inline-cta" to={`/tickets/${card.product_id}`}>티켓 보기</Link> : null;
    case "PACKAGE":
      return card.package_id ? <Link className="inline-cta" to={`/packages/${card.package_id}`}>패키지 보기</Link> : null;
    case "POI":
      return <Link className="inline-cta" to="/nearby">지도 보기</Link>;
    default:
      return null;
  }
}

function buildChatContext(searchContext: StaySearchInput, sourceTypes: readonly string[]): Record<string, unknown> {
  const sessionId = ensureConciergeSessionId();
  const authUser = getAuthUser();
  const normalizedSourceTypes = normalizeSourceTypes(sourceTypes);
  return {
    city: searchContext.city ?? "Seoul",
    session_id: sessionId,
    user_id: authUser?.userId,
    check_in: searchContext.checkIn,
    check_out: searchContext.checkOut,
    days: getNightCount(searchContext.checkIn, searchContext.checkOut) + 1,
    companions: companionsByGuests(searchContext),
    source_types: normalizedSourceTypes,
    source_type_scope: sourceTypeScope(normalizedSourceTypes),
    guests: {
      rooms: searchContext.guests.rooms,
      adults: searchContext.guests.adults,
      children: searchContext.guests.children,
      children_ages: searchContext.guests.childrenAges.slice(0, searchContext.guests.children),
    },
  };
}

function filterToken(filter: SearchHandoffFilter): string {
  return `${filter.key}:${filter.value}`;
}

function normalizeCityCode(value: string): string | null {
  const raw = value.trim();
  if (!raw) {
    return null;
  }
  return CITY_CODE_ALIAS[raw.toLowerCase()] ?? raw;
}

function cityLabelFromCode(cityCode: string): string {
  return CITY_CODE_LABEL[cityCode] ?? cityCode;
}

function applySearchPatch(
  current: StaySearchInput,
  patch: SearchHandoffSearchPatch,
): StaySearchInput {
  let next: StaySearchInput = {
    ...current,
    guests: {
      ...current.guests,
      childrenAges: [...current.guests.childrenAges],
    },
  };

  if (patch.city) {
    const normalizedCity = normalizeCityCode(patch.city);
    if (normalizedCity) {
      next = {
        ...next,
        city: normalizedCity,
        placeId: `city:${normalizedCity}`,
        placeLabel: cityLabelFromCode(normalizedCity),
        district: undefined,
      };
    }
  }

  if (typeof patch.days === "number" && Number.isFinite(patch.days)) {
    const nights = Math.max(1, Math.trunc(patch.days) - 1);
    const checkOut = plusDays(next.checkIn, nights);
    if (checkOut) {
      next = {
        ...next,
        checkOut,
      };
    }
  }

  if (patch.companions) {
    next = {
      ...next,
      guests: applyCompanionPreset(next.guests, patch.companions),
    };
  }

  return next;
}

function mergeSearchPatch(
  base: SearchHandoffSearchPatch,
  next: SearchHandoffSearchPatch,
): SearchHandoffSearchPatch {
  return {
    city: next.city ?? base.city,
    days: next.days ?? base.days,
    companions: next.companions ?? base.companions,
  };
}

function applyCompanionPreset(
  guests: StaySearchInput["guests"],
  companions: string,
): StaySearchInput["guests"] {
  const normalizeChildrenAges = (count: number, existing: number[]) => {
    const next = existing.slice(0, count);
    while (next.length < count) {
      next.push(7);
    }
    return next;
  };

  switch (companions) {
    case "FAMILY": {
      const children = Math.max(guests.children, 1);
      return {
        rooms: guests.rooms,
        adults: Math.max(guests.adults, 2),
        children,
        childrenAges: normalizeChildrenAges(children, guests.childrenAges),
      };
    }
    case "COUPLE":
      return { rooms: guests.rooms, adults: 2, children: 0, childrenAges: [] };
    case "SOLO":
      return { rooms: guests.rooms, adults: 1, children: 0, childrenAges: [] };
    case "FRIENDS":
      return { rooms: guests.rooms, adults: Math.max(guests.adults, 3), children: 0, childrenAges: [] };
    default:
      return guests;
  }
}

function plusDays(baseDate: string, days: number): string | null {
  const base = Date.parse(`${baseDate}T00:00:00Z`);
  if (!Number.isFinite(base)) {
    return null;
  }
  const next = new Date(base + (days * 24 * 60 * 60 * 1000));
  const year = next.getUTCFullYear();
  const month = String(next.getUTCMonth() + 1).padStart(2, "0");
  const day = String(next.getUTCDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function describeSearchPatch(patch: SearchHandoffSearchPatch): string {
  const parts: string[] = [];
  if (patch.city) {
    const normalizedCity = normalizeCityCode(patch.city);
    if (normalizedCity) {
      parts.push(cityLabelFromCode(normalizedCity));
    }
  }
  if (typeof patch.days === "number" && Number.isFinite(patch.days)) {
    const days = Math.max(1, Math.trunc(patch.days));
    const nights = Math.max(1, days - 1);
    parts.push(`${nights}박 ${days}일`);
  }
  if (patch.companions) {
    const companionsText = ({
      FAMILY: "가족",
      COUPLE: "커플",
      SOLO: "1인",
      FRIENDS: "친구",
    } as Record<string, string>)[patch.companions] ?? patch.companions;
    parts.push(companionsText);
  }
  return parts.join(" · ");
}

function extractSearchHandoff(
  contextUsed: Record<string, unknown> | undefined,
): {
  summary: string;
  filters: SearchHandoffFilter[];
  confidence: number | null;
  profileApplied: boolean;
  rationale: string[];
  sortHint: SearchHandoffSortHint | null;
  sourceHints: SearchHandoffSourceHint[];
  clarifyQuestions: string[];
  clarifyActions: SearchHandoffClarifyAction[];
  clarifyRequired: boolean;
  missingSlots: string[];
  searchPatch: SearchHandoffSearchPatch;
  recommendedSourceTypes: string[];
} {
  if (!contextUsed) {
    return {
      summary: "",
      filters: [],
      confidence: null,
      profileApplied: false,
      rationale: [],
      sortHint: null,
      sourceHints: [],
      clarifyQuestions: [],
      clarifyActions: [],
      clarifyRequired: false,
      missingSlots: [],
      searchPatch: {},
      recommendedSourceTypes: [],
    };
  }

  const handoff = contextUsed.search_handoff;
  if (!handoff || typeof handoff !== "object") {
    return {
      summary: "",
      filters: [],
      confidence: null,
      profileApplied: false,
      rationale: [],
      sortHint: null,
      sourceHints: [],
      clarifyQuestions: [],
      clarifyActions: [],
      clarifyRequired: false,
      missingSlots: [],
      searchPatch: {},
      recommendedSourceTypes: [],
    };
  }

  const row = handoff as {
    summary?: unknown;
    recommended_filters?: unknown;
    confidence?: unknown;
    profile_applied?: unknown;
    rationale?: unknown;
    sort_hint?: unknown;
    recommended_source_hints?: unknown;
    clarify_questions?: unknown;
    clarify_actions?: unknown;
    clarify_required?: unknown;
    missing_slots?: unknown;
    search_patch?: unknown;
    recommended_source_types?: unknown;
    city?: unknown;
    days?: unknown;
    companions?: unknown;
  };
  const summary = typeof row.summary === "string" ? row.summary : "";
  const confidence = typeof row.confidence === "number" && Number.isFinite(row.confidence)
    ? Math.min(1, Math.max(0, row.confidence))
    : null;
  const profileApplied = row.profile_applied === true;
  const rationale = Array.isArray(row.rationale)
    ? row.rationale
        .map((item) => (typeof item === "string" ? item.trim() : ""))
        .filter((item) => item.length > 0)
        .slice(0, 3)
    : [];
  const sortHintRow = row.sort_hint && typeof row.sort_hint === "object"
    ? row.sort_hint as Record<string, unknown>
    : undefined;
  const sortHintValue = typeof sortHintRow?.value === "string"
    ? sortHintRow.value.trim()
    : "";
  const sortHint = ALLOWED_SORT_VALUES.has(sortHintValue)
    ? {
        value: sortHintValue,
        label: (typeof sortHintRow?.label === "string" && sortHintRow.label.trim())
          ? sortHintRow.label.trim()
          : (SORT_VALUE_LABELS[sortHintValue] ?? sortHintValue),
        reason: typeof sortHintRow?.reason === "string" ? sortHintRow.reason : undefined,
      }
    : null;
  const sourceHints = Array.isArray(row.recommended_source_hints)
    ? row.recommended_source_hints
        .map((item): SearchHandoffSourceHint | null => {
          if (!item || typeof item !== "object") {
            return null;
          }
          const candidate = item as Record<string, unknown>;
          const sourceType = typeof candidate.source_type === "string"
            ? candidate.source_type.trim().toUpperCase()
            : "";
          const prompt = typeof candidate.prompt === "string" ? candidate.prompt.trim() : "";
          if (!ALLOWED_HINT_SOURCE_TYPES.has(sourceType) || !prompt) {
            return null;
          }
          const label = typeof candidate.label === "string" && candidate.label.trim()
            ? candidate.label.trim()
            : (SOURCE_TYPE_LABELS[sourceType] ?? sourceType);
          return {
            sourceType,
            label,
            reason: typeof candidate.reason === "string" ? candidate.reason : undefined,
            prompt,
          };
        })
        .filter((item): item is SearchHandoffSourceHint => item !== null)
        .slice(0, 4)
    : [];
  const clarifyQuestions = Array.isArray(row.clarify_questions)
    ? row.clarify_questions
        .map((item) => (typeof item === "string" ? item.trim() : ""))
        .filter((item) => item.length > 0)
        .slice(0, 3)
    : [];
  const clarifyActions = Array.isArray(row.clarify_actions)
    ? row.clarify_actions
        .map((item): SearchHandoffClarifyAction | null => {
          if (!item || typeof item !== "object") {
            return null;
          }
          const action = item as Record<string, unknown>;
          const slot = typeof action.slot === "string" ? action.slot.trim().toLowerCase() : "";
          const label = typeof action.label === "string" ? action.label.trim() : "";
          const prompt = typeof action.prompt === "string" ? action.prompt.trim() : "";
          if (!(slot in MISSING_SLOT_LABELS) || !label || !prompt) {
            return null;
          }

          const actionPatchRow = action.search_patch && typeof action.search_patch === "object"
            ? action.search_patch as Record<string, unknown>
            : undefined;
          const actionPatch: SearchHandoffSearchPatch = {};
          if (typeof actionPatchRow?.city === "string") {
            const normalizedCity = normalizeCityCode(actionPatchRow.city);
            if (normalizedCity) {
              actionPatch.city = normalizedCity;
            }
          }
          if (typeof actionPatchRow?.days === "number" && Number.isFinite(actionPatchRow.days)) {
            const rounded = Math.trunc(actionPatchRow.days);
            if (rounded >= 1 && rounded <= 30) {
              actionPatch.days = rounded;
            }
          }
          if (typeof actionPatchRow?.companions === "string") {
            const companion = actionPatchRow.companions.trim().toUpperCase();
            if (["FAMILY", "COUPLE", "SOLO", "FRIENDS"].includes(companion)) {
              actionPatch.companions = companion;
            }
          }

          const actionSourceTypes = Array.isArray(action.recommended_source_types)
            ? normalizeSourceTypes(
                action.recommended_source_types
                  .map((candidate) => (typeof candidate === "string" ? candidate.trim() : ""))
                  .filter((candidate) => candidate.length > 0),
              )
            : [];

          return {
            slot,
            label,
            prompt,
            searchPatch: actionPatch,
            recommendedSourceTypes: actionSourceTypes,
          };
        })
        .filter((item): item is SearchHandoffClarifyAction => item !== null)
        .slice(0, 8)
    : [];
  const clarifyRequired = row.clarify_required === true;
  const missingSlots = Array.isArray(row.missing_slots)
    ? row.missing_slots
        .map((item) => (typeof item === "string" ? item.trim().toLowerCase() : ""))
        .filter((item) => item in MISSING_SLOT_LABELS)
        .slice(0, 5)
    : [];
  const recommendedSourceTypes = Array.isArray(row.recommended_source_types)
    ? normalizeSourceTypes(
        row.recommended_source_types
          .map((item) => (typeof item === "string" ? item.trim() : ""))
          .filter((item) => item.length > 0),
      )
    : [];
  const patchRow = row.search_patch && typeof row.search_patch === "object"
    ? row.search_patch as Record<string, unknown>
    : undefined;
  const patchCity = typeof patchRow?.city === "string" ? patchRow.city : row.city;
  const patchDays = typeof patchRow?.days === "number" ? patchRow.days : row.days;
  const patchCompanions = typeof patchRow?.companions === "string" ? patchRow.companions : row.companions;

  const searchPatch: SearchHandoffSearchPatch = {};
  if (typeof patchCity === "string") {
    const normalizedCity = normalizeCityCode(patchCity);
    if (normalizedCity) {
      searchPatch.city = normalizedCity;
    }
  }
  if (typeof patchDays === "number" && Number.isFinite(patchDays)) {
    const rounded = Math.trunc(patchDays);
    if (rounded >= 1 && rounded <= 30) {
      searchPatch.days = rounded;
    }
  }
  if (typeof patchCompanions === "string") {
    const companion = patchCompanions.trim().toUpperCase();
    if (["FAMILY", "COUPLE", "SOLO", "FRIENDS"].includes(companion)) {
      searchPatch.companions = companion;
    }
  }
  const filters = Array.isArray(row.recommended_filters)
    ? row.recommended_filters
        .map((item): SearchHandoffFilter | null => {
          if (!item || typeof item !== "object") {
            return null;
          }
          const candidate = item as Record<string, unknown>;
          const key = typeof candidate.key === "string" ? candidate.key.trim() : "";
          const value = typeof candidate.value === "string" ? candidate.value.trim() : "";
          if (!key || !value) {
            return null;
          }
          const label = typeof candidate.label === "string" && candidate.label.trim()
            ? candidate.label.trim()
            : `${key}:${value}`;
          return {
            key,
            value,
            label,
            reason: typeof candidate.reason === "string" ? candidate.reason : undefined,
          };
        })
        .filter((item): item is SearchHandoffFilter => item !== null)
        .slice(0, 6)
    : [];

  return {
    summary,
    filters,
    confidence,
    profileApplied,
    rationale,
    sortHint,
    sourceHints,
    clarifyQuestions,
    clarifyActions,
    clarifyRequired,
    missingSlots,
    searchPatch,
    recommendedSourceTypes,
  };
}

function companionsByGuests(searchContext: StaySearchInput): string {
  if (searchContext.guests.children > 0) {
    return "FAMILY";
  }
  if (searchContext.guests.adults === 1) {
    return "SOLO";
  }
  if (searchContext.guests.adults === 2) {
    return "COUPLE";
  }
  return "FRIENDS";
}

function getNightCount(checkIn: string, checkOut: string): number {
  const start = new Date(`${checkIn}T00:00:00Z`).getTime();
  const end = new Date(`${checkOut}T00:00:00Z`).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end)) {
    return 2;
  }
  const diff = Math.round((end - start) / (1000 * 60 * 60 * 24));
  return Math.min(30, Math.max(1, diff));
}

function emptyDockState(): ConciergeDockState {
  return {
    open: false,
    activeSourceTypes: [...SOURCE_TYPE_FILTERS.city],
    messageDraft: "",
    messages: [],
    answer: "",
    cards: [],
    followups: [],
    routeLabel: "-",
    handoffSummary: "",
    handoffConfidence: null,
    handoffProfileApplied: false,
    handoffRationale: [],
    handoffSortHint: null,
    handoffSourceHints: [],
    handoffClarifyQuestions: [],
    handoffClarifyActions: [],
    handoffClarifyRequired: false,
    handoffMissingSlots: [],
    handoffSearchPatch: {},
    handoffRecommendedSourceTypes: [],
    handoffFilters: [],
    selectedHandoffFilterKeys: [],
  };
}

function loadConciergeDockState(): ConciergeDockState {
  const defaults = emptyDockState();
  if (typeof window === "undefined") {
    return defaults;
  }
  const raw = window.localStorage.getItem(CONCIERGE_DOCK_STATE_KEY);
  if (!raw) {
    return defaults;
  }
  try {
    const parsed = JSON.parse(raw) as Partial<ConciergeDockState>;
    const activeSourceTypes = normalizeSourceTypes(Array.isArray(parsed.activeSourceTypes) ? parsed.activeSourceTypes : []);
    const isMessage = (item: unknown): item is ChatMessage => (
      !!item
      && typeof item === "object"
      && ("role" in item)
      && ("text" in item)
      && ((item as { role: string }).role === "user" || (item as { role: string }).role === "assistant")
      && typeof (item as { text: unknown }).text === "string"
    );
    const messages = Array.isArray(parsed.messages) ? parsed.messages.filter(isMessage).slice(-20) : [];
    return {
      ...defaults,
      open: parsed.open === true,
      activeSourceTypes,
      messageDraft: typeof parsed.messageDraft === "string" ? parsed.messageDraft : "",
      messages,
      answer: typeof parsed.answer === "string" ? parsed.answer : "",
      cards: Array.isArray(parsed.cards) ? parsed.cards.slice(0, 6) : [],
      followups: Array.isArray(parsed.followups) ? parsed.followups.filter((item) => typeof item === "string").slice(0, 6) : [],
      routeLabel: typeof parsed.routeLabel === "string" && parsed.routeLabel.trim() ? parsed.routeLabel : "-",
      handoffSummary: typeof parsed.handoffSummary === "string" ? parsed.handoffSummary : "",
      handoffConfidence: typeof parsed.handoffConfidence === "number" && Number.isFinite(parsed.handoffConfidence)
        ? Math.min(1, Math.max(0, parsed.handoffConfidence))
        : null,
      handoffProfileApplied: parsed.handoffProfileApplied === true,
      handoffRationale: Array.isArray(parsed.handoffRationale)
        ? parsed.handoffRationale.filter((item) => typeof item === "string").slice(0, 6)
        : [],
      handoffSortHint: parsed.handoffSortHint && typeof parsed.handoffSortHint === "object"
        ? parsed.handoffSortHint
        : null,
      handoffSourceHints: Array.isArray(parsed.handoffSourceHints) ? parsed.handoffSourceHints.slice(0, 6) : [],
      handoffClarifyQuestions: Array.isArray(parsed.handoffClarifyQuestions)
        ? parsed.handoffClarifyQuestions.filter((item) => typeof item === "string").slice(0, 6)
        : [],
      handoffClarifyActions: Array.isArray(parsed.handoffClarifyActions) ? parsed.handoffClarifyActions.slice(0, 8) : [],
      handoffClarifyRequired: parsed.handoffClarifyRequired === true,
      handoffMissingSlots: Array.isArray(parsed.handoffMissingSlots)
        ? parsed.handoffMissingSlots.filter((item) => typeof item === "string").slice(0, 6)
        : [],
      handoffSearchPatch: parsed.handoffSearchPatch && typeof parsed.handoffSearchPatch === "object"
        ? parsed.handoffSearchPatch
        : {},
      handoffRecommendedSourceTypes: normalizeSourceTypes(
        Array.isArray(parsed.handoffRecommendedSourceTypes) ? parsed.handoffRecommendedSourceTypes : [],
      ),
      handoffFilters: Array.isArray(parsed.handoffFilters) ? parsed.handoffFilters.slice(0, 8) : [],
      selectedHandoffFilterKeys: Array.isArray(parsed.selectedHandoffFilterKeys)
        ? parsed.selectedHandoffFilterKeys.filter((item) => typeof item === "string").slice(0, 8)
        : [],
    };
  } catch {
    return defaults;
  }
}

function saveConciergeDockState(state: ConciergeDockState): void {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(CONCIERGE_DOCK_STATE_KEY, JSON.stringify(state));
  } catch {
    // localStorage quota errors must not block chat flow.
  }
}

function ensureConciergeSessionId(): string {
  if (typeof window === "undefined") {
    return "server";
  }
  const existing = window.localStorage.getItem(CONCIERGE_SESSION_KEY);
  if (existing && existing.trim().length >= 12) {
    return existing;
  }
  const generated = `cg-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  window.localStorage.setItem(CONCIERGE_SESSION_KEY, generated);
  return generated;
}

async function recommend(payload: Record<string, unknown>): Promise<ChatData> {
  const token = getAuthBearerToken();
  const response = await fetch(`${API_BASE}/v1/chat/recommend`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw await parseApiError(response);
  }
  const envelope = (await response.json()) as { data: ChatData };
  return envelope.data;
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
    throw await parseApiError(response);
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

async function parseApiError(response: Response): Promise<ApiError> {
  try {
    const payload = (await response.json()) as { error?: ApiError };
    return payload.error ?? { code: "ERROR", message: `HTTP ${response.status}` };
  } catch {
    return { code: "ERROR", message: `HTTP ${response.status}` };
  }
}

async function sendWidgetTelemetry(payload: {
  event_name: TelemetryEventName;
  source: string;
  route: string;
  filter_count?: number;
  handoff_confidence?: number;
  handoff_profile_applied?: boolean;
  clarify_required?: boolean;
  missing_slot_count?: number;
  source_type_scope?: string;
  clarify_slot?: string;
  sort_value?: string;
  target_source_type?: string;
}) {
  const token = getAuthBearerToken();
  try {
    await fetch(`${API_BASE}/v1/telemetry/events`, {
      method: "POST",
      keepalive: true,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(payload),
    });
  } catch {
    // Telemetry must not break user flow.
  }
}

function normalizeSourceTypes(sourceTypes: readonly string[]): string[] {
  const seen = new Set<string>();
  const normalized = sourceTypes
    .map((item) => item.trim().toUpperCase())
    .filter((item) => VALID_SOURCE_TYPES.has(item))
    .filter((item) => {
      if (seen.has(item)) {
        return false;
      }
      seen.add(item);
      return true;
    });

  if (normalized.length > 0) {
    return normalized;
  }

  return [...SOURCE_TYPE_FILTERS.city];
}

function sourceTypeScope(sourceTypes: readonly string[]): string {
  return normalizeSourceTypes(sourceTypes).join(",");
}

function sourceTypeScopeLabel(sourceTypes: readonly string[]): string {
  return normalizeSourceTypes(sourceTypes)
    .map((item) => SOURCE_TYPE_LABELS[item] ?? item)
    .join(" + ");
}

function describeMissingSlots(missingSlots: readonly string[]): string {
  return missingSlots
    .map((slot) => MISSING_SLOT_LABELS[slot] ?? slot)
    .join(" · ");
}

function inferSourceTypesFromPrompt(message: string): string[] {
  const normalized = message.trim().toLowerCase();
  if (!normalized) {
    return [];
  }
  const detected: string[] = [];
  const include = (sourceType: string) => {
    if (!detected.includes(sourceType)) {
      detected.push(sourceType);
    }
  };
  const containsAny = (keywords: readonly string[]) => keywords.some((keyword) => normalized.includes(keyword));

  if (containsAny(["숙소", "호텔", "리조트", "펜션", "모텔", "stay", "hotel", "property"])) {
    include("PROPERTY");
  }
  if (containsAny(["패키지", "package", "bundle", "항공+숙소", "항공 + 숙소"])) {
    include("PACKAGE");
  }
  if (containsAny(["티켓", "입장권", "전시권", "공연", "ticket", "pass"])) {
    include("TICKET");
  }
  if (containsAny(["맛집", "관광", "명소", "주변", "식당", "카페", "food", "poi", "attraction", "shopping"])) {
    include("POI");
  }

  return normalizeSourceTypes(detected);
}
