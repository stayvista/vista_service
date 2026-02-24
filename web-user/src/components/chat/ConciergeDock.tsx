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
  | "ai_widget_prompt_autopatch"
  | "ai_widget_prompt_reuse_click"
  | "ai_widget_followup_click"
  | "ai_widget_clarify_click"
  | "ai_widget_clarify_action_click"
  | "ai_widget_quick_fix_click"
  | "ai_widget_sort_hint_click"
  | "ai_widget_scope_hint_click"
  | "ai_widget_filter_apply"
  | "ai_widget_search_handoff"
  | "ai_widget_view_results"
  | "ai_widget_answer_feedback"
  | "ai_widget_answer_copy_click"
  | "ai_widget_card_type_filter_click"
  | "ai_widget_card_list_toggle_click"
  | "ai_widget_card_save_click"
  | "ai_widget_card_followup_click"
  | "ai_widget_regenerate_click"
  | "ai_widget_search_blocked"
  | "ai_widget_slot_chip_click"
  | "ai_widget_filter_bulk_apply"
  | "ai_widget_generation_cancel";

type CardTypeFilter = "ALL" | "PROPERTY" | "PACKAGE" | "TICKET" | "POI";
type CardListState = "expanded" | "collapsed";
type CardSaveState = "saved" | "unsaved";

type ConciergeDockState = {
  open: boolean;
  activeSourceTypes: string[];
  messageDraft: string;
  lastPrompt: string;
  answerFeedback: "positive" | "negative" | null;
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
  savedCards: ChatCard[];
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
const CITY_ALIAS_ENTRIES = Object.entries(CITY_CODE_ALIAS).sort((a, b) => b[0].length - a[0].length);

const VALID_SOURCE_TYPES = new Set(["PROPERTY", "TICKET", "PACKAGE", "POI"]);
const SOURCE_TYPE_LABELS: Record<string, string> = {
  PROPERTY: "숙소",
  PACKAGE: "패키지",
  TICKET: "티켓",
  POI: "주변 추천",
};
const CARD_TYPE_FILTER_ORDER: readonly CardTypeFilter[] = ["ALL", "PROPERTY", "PACKAGE", "TICKET", "POI"];
const CARD_TYPE_FILTER_LABELS: Record<CardTypeFilter, string> = {
  ALL: "전체",
  PROPERTY: "숙소",
  PACKAGE: "패키지",
  TICKET: "티켓",
  POI: "명소",
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
const SLOT_ORDER = ["city", "days", "companions", "budget", "preferences"] as const;

export function ConciergeDock({ searchContext, onSearch }: Props) {
  const navigate = useNavigate();
  const restoredState = useMemo(() => loadConciergeDockState(), []);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const composerRef = useRef<HTMLTextAreaElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const [open, setOpen] = useState(restoredState.open);
  const [isCompactViewport, setIsCompactViewport] = useState(
    () => typeof window !== "undefined" && window.innerWidth <= 1080,
  );
  const [activeSourceTypes, setActiveSourceTypes] = useState<string[]>(
    restoredState.activeSourceTypes.length > 0 ? restoredState.activeSourceTypes : [...SOURCE_TYPE_FILTERS.city],
  );
  const [message, setMessage] = useState(restoredState.messageDraft);
  const [lastPrompt, setLastPrompt] = useState(restoredState.lastPrompt);
  const [answerFeedback, setAnswerFeedback] = useState<"positive" | "negative" | null>(restoredState.answerFeedback);
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
  const [savedCards, setSavedCards] = useState<ChatCard[]>(restoredState.savedCards);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copyDone, setCopyDone] = useState(false);
  const [autopatchNotice, setAutopatchNotice] = useState("");
  const [selectedCardType, setSelectedCardType] = useState<CardTypeFilter>("ALL");
  const [expandedCards, setExpandedCards] = useState(false);

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
  const slotChips = useMemo(() => {
    const missing = new Set(handoffMissingSlots);
    return SLOT_ORDER.map((slot) => ({
      slot,
      label: MISSING_SLOT_LABELS[slot] ?? slot,
      missing: missing.has(slot),
      prompt: slotClarifyPrompt(slot, searchContext),
    }));
  }, [handoffMissingSlots, searchContext]);
  const quickFixSlots = useMemo(
    () => slotChips.filter((slot) => slot.missing).slice(0, 3),
    [slotChips],
  );
  const slotCompletionRate = useMemo(() => {
    const total = SLOT_ORDER.length;
    const missingCount = slotChips.filter((slot) => slot.missing).length;
    return Math.round(((total - missingCount) / total) * 100);
  }, [slotChips]);
  const recentUserPrompts = useMemo(() => {
    const unique = new Set<string>();
    const collected: string[] = [];

    const append = (value: string) => {
      const trimmed = value.trim();
      if (trimmed.length < 4 || unique.has(trimmed)) {
        return;
      }
      unique.add(trimmed);
      collected.push(trimmed);
    };

    if (lastPrompt.trim()) {
      append(lastPrompt);
    }
    for (let idx = messages.length - 1; idx >= 0; idx -= 1) {
      const item = messages[idx];
      if (item.role !== "user") {
        continue;
      }
      append(item.text);
      if (collected.length >= 5) {
        break;
      }
    }
    return collected.slice(0, 5);
  }, [lastPrompt, messages]);
  const cardTypeCounts = useMemo(() => {
    const counts: Record<CardTypeFilter, number> = {
      ALL: cards.length,
      PROPERTY: 0,
      PACKAGE: 0,
      TICKET: 0,
      POI: 0,
    };
    cards.forEach((card) => {
      const normalizedType = normalizeCardType(card.type);
      if (normalizedType && normalizedType !== "ALL") {
        counts[normalizedType] += 1;
      }
    });
    return counts;
  }, [cards]);
  const availableCardTypes = useMemo(
    () => CARD_TYPE_FILTER_ORDER.filter((type) => (type === "ALL" ? cards.length > 0 : cardTypeCounts[type] > 0)),
    [cards.length, cardTypeCounts],
  );
  const visibleCards = useMemo(
    () => (
      selectedCardType === "ALL"
        ? cards
        : cards.filter((card) => normalizeCardType(card.type) === selectedCardType)
    ),
    [cards, selectedCardType],
  );
  const savedCardKeys = useMemo(
    () => new Set(savedCards.map((card) => cardToken(card))),
    [savedCards],
  );
  const visibleSavedCards = useMemo(
    () => (
      selectedCardType === "ALL"
        ? savedCards
        : savedCards.filter((card) => normalizeCardType(card.type) === selectedCardType)
    ),
    [savedCards, selectedCardType],
  );
  const renderedCards = useMemo(
    () => (expandedCards ? visibleCards : visibleCards.slice(0, 4)),
    [expandedCards, visibleCards],
  );
  const bulkSelectableFilterTokens = useMemo(() => {
    const nonSortTokens = handoffFilters
      .filter((item) => item.key !== "sort")
      .map((item) => filterToken(item));
    const sortFilters = handoffFilters.filter((item) => item.key === "sort");
    if (sortFilters.length === 0) {
      return nonSortTokens;
    }

    const selectedSortToken = selectedHandoffFilterKeys.find((item) => (
      item.startsWith("sort:") && sortFilters.some((candidate) => filterToken(candidate) === item)
    ));
    if (selectedSortToken) {
      return [...nonSortTokens, selectedSortToken];
    }

    if (handoffSortHint && ALLOWED_SORT_VALUES.has(handoffSortHint.value)) {
      const hintedSort = sortFilters.find((item) => item.value === handoffSortHint.value);
      if (hintedSort) {
        return [...nonSortTokens, filterToken(hintedSort)];
      }
    }

    return [...nonSortTokens, filterToken(sortFilters[0])];
  }, [handoffFilters, handoffSortHint, selectedHandoffFilterKeys]);
  const areAllHandoffFiltersSelected = useMemo(() => {
    if (bulkSelectableFilterTokens.length === 0) {
      return false;
    }
    const selected = new Set(selectedHandoffFilterKeys);
    return bulkSelectableFilterTokens.every((token) => selected.has(token));
  }, [bulkSelectableFilterTokens, selectedHandoffFilterKeys]);
  const launchBlockedReason = useMemo(() => {
    if (!handoffClarifyRequired || handoffMissingSlots.length === 0) {
      return null;
    }
    return `검색 적용 전에 ${describeMissingSlots(handoffMissingSlots)} 정보를 먼저 알려주세요.`;
  }, [handoffClarifyRequired, handoffMissingSlots]);

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
    return () => {
      abortRef.current?.abort();
      abortRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!copyDone) {
      return undefined;
    }
    const timer = window.setTimeout(() => {
      setCopyDone(false);
    }, 1800);
    return () => window.clearTimeout(timer);
  }, [copyDone]);

  useEffect(() => {
    if (!autopatchNotice) {
      return undefined;
    }
    const timer = window.setTimeout(() => {
      setAutopatchNotice("");
    }, 2200);
    return () => window.clearTimeout(timer);
  }, [autopatchNotice]);

  useEffect(() => {
    if (selectedCardType === "ALL") {
      return;
    }
    if (cardTypeCounts[selectedCardType] > 0) {
      return;
    }
    setSelectedCardType("ALL");
  }, [selectedCardType, cardTypeCounts]);

  useEffect(() => {
    if (expandedCards && visibleCards.length <= 4) {
      setExpandedCards(false);
    }
  }, [expandedCards, visibleCards.length]);

  useEffect(() => {
    saveConciergeDockState({
      open,
      activeSourceTypes,
      messageDraft: message,
      lastPrompt,
      answerFeedback,
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
      savedCards: savedCards.slice(0, 12),
    });
  }, [
    open,
    activeSourceTypes,
    message,
    lastPrompt,
    answerFeedback,
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
    savedCards,
  ]);

  function resetConversation() {
    setMessage("");
    setLastPrompt("");
    setAnswerFeedback(null);
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
    setCopyDone(false);
    setAutopatchNotice("");
    setSelectedCardType("ALL");
    setExpandedCards(false);
  }

  async function ask(
    input: string,
    sourceTypes: readonly string[] = activeSourceTypes,
    searchContextOverride?: StaySearchInput,
  ) {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
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
    setLastPrompt(input);
    setAnswerFeedback(null);
    setCopyDone(false);
    setSelectedCardType("ALL");
    setExpandedCards(false);

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
      }, controller.signal);

      if (!donePayload) {
        donePayload = await recommend(payload, controller.signal);
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
      if (isAbortError(e)) {
        setError("추천 생성을 중단했습니다. 조건을 보완해 다시 요청해 주세요.");
      } else {
        const err = e as ApiError;
        setError(`${err.code ?? "ERROR"}: ${err.message ?? "AI 추천 생성 실패"}`);
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
      setLoading(false);
      setStreamingAnswer("");
    }
  }

  function submitPrompt() {
    if (!canSubmit) {
      return;
    }
    const current = message.trim();
    const inferredPatch = inferSearchPatchFromPrompt(current);
    const hasAutoPatch = patchFieldCount(inferredPatch) > 0;
    const inferredSourceTypes = inferSourceTypesFromPrompt(current);
    const nextSourceTypes = inferredSourceTypes.length > 0 ? inferredSourceTypes : activeSourceTypes;
    if (inferredSourceTypes.length > 0) {
      setActiveSourceTypes(nextSourceTypes);
    }
    setMessage("");
    const scope = sourceTypeScope(nextSourceTypes);
    const patchedContext = hasAutoPatch ? applySearchPatch(searchContext, inferredPatch) : searchContext;
    if (hasAutoPatch) {
      setAutopatchNotice(`자동 반영: ${describeSearchPatch(inferredPatch)}`);
      track("ai_widget_prompt_autopatch", "text_input", {
        source_type_scope: scope,
        auto_patch_count: patchFieldCount(inferredPatch),
      });
    } else {
      setAutopatchNotice("");
    }
    track("ai_widget_prompt_submit", "text_input", { source_type_scope: scope });
    void ask(current, nextSourceTypes, patchedContext);
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
    if (launchBlockedReason) {
      setError(launchBlockedReason);
      track("ai_widget_search_blocked", "search_cta", {
        clarify_required: true,
        missing_slot_count: handoffMissingSlots.length,
        source_type_scope: sourceTypeScope(activeSourceTypes),
      });
      if (handoffClarifyQuestions.length > 0) {
        void ask(handoffClarifyQuestions[0], activeSourceTypes);
      }
      return;
    }

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

  function submitAnswerFeedback(feedback: "positive" | "negative") {
    setAnswerFeedback(feedback);
    track("ai_widget_answer_feedback", "feedback", {
      feedback_value: feedback,
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
  }

  function regenerateAnswer() {
    if (!lastPrompt || loading) {
      return;
    }
    track("ai_widget_regenerate_click", "results_cta", {
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
    void ask(lastPrompt, activeSourceTypes);
  }

  function cancelGeneration() {
    if (!loading || !abortRef.current) {
      return;
    }
    abortRef.current.abort();
    abortRef.current = null;
    track("ai_widget_generation_cancel", "results_cta", {
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
  }

  function selectAllHandoffFilters() {
    if (bulkSelectableFilterTokens.length === 0) {
      return;
    }
    setSelectedHandoffFilterKeys(bulkSelectableFilterTokens);
    track("ai_widget_filter_bulk_apply", "filter_bulk", {
      filter_count: bulkSelectableFilterTokens.length,
      bulk_action: "select_all",
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
  }

  function clearAllHandoffFilters() {
    if (selectedHandoffFilterKeys.length === 0) {
      return;
    }
    setSelectedHandoffFilterKeys([]);
    track("ai_widget_filter_bulk_apply", "filter_bulk", {
      filter_count: 0,
      bulk_action: "clear_all",
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
  }

  function viewAllCards() {
    track("ai_widget_view_results", "results_cta");
    const targetCards = visibleCards.length > 0 ? visibleCards : cards;
    const typeSet = new Set(targetCards.map((item) => item.type.toUpperCase()));
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

  function selectCardType(type: CardTypeFilter) {
    if (selectedCardType === type) {
      return;
    }
    setSelectedCardType(type);
    setExpandedCards(false);
    const visibleCount = type === "ALL" ? cards.length : cardTypeCounts[type];
    track("ai_widget_card_type_filter_click", "results_cta", {
      target_source_type: type,
      source_type_scope: sourceTypeScope(activeSourceTypes),
      visible_card_count: visibleCount,
    });
  }

  function toggleCardList() {
    const nextExpanded = !expandedCards;
    setExpandedCards(nextExpanded);
    track("ai_widget_card_list_toggle_click", "results_cta", {
      card_list_state: nextExpanded ? "expanded" : "collapsed",
      source_type_scope: sourceTypeScope(activeSourceTypes),
      visible_card_count: nextExpanded ? visibleCards.length : Math.min(4, visibleCards.length),
    });
  }

  function toggleCardSave(card: ChatCard) {
    const token = cardToken(card);
    const sourceType = normalizeCardType(card.type);
    if (!sourceType || sourceType === "ALL") {
      return;
    }
    let saveState: CardSaveState = "saved";
    let nextCount = 0;
    setSavedCards((prev) => {
      const exists = prev.some((item) => cardToken(item) === token);
      if (exists) {
        saveState = "unsaved";
        const next = prev.filter((item) => cardToken(item) !== token);
        nextCount = next.length;
        return next;
      }
      saveState = "saved";
      const deduped = prev.filter((item) => cardToken(item) !== token);
      const next = [card, ...deduped].slice(0, 12);
      nextCount = next.length;
      return next;
    });
    track("ai_widget_card_save_click", "results_cta", {
      source_type_scope: sourceTypeScope(activeSourceTypes),
      target_source_type: sourceType,
      card_save_state: saveState,
      saved_card_count: nextCount,
    });
  }

  function clearSavedCards() {
    setSavedCards([]);
  }

  function askCardFollowup(card: ChatCard, origin: "results_card" | "saved_card") {
    if (loading) {
      return;
    }
    const sourceType = normalizeCardType(card.type);
    if (!sourceType || sourceType === "ALL") {
      return;
    }
    const nextSourceTypes = normalizeSourceTypes([sourceType]);
    setActiveSourceTypes(nextSourceTypes);
    const prompt = buildCardFollowupPrompt(card, sourceType, cityLabel, nights);
    track("ai_widget_card_followup_click", origin, {
      source_type_scope: sourceTypeScope(nextSourceTypes),
      target_source_type: sourceType,
    });
    void ask(prompt, nextSourceTypes);
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
      feedback_value?: "positive" | "negative";
      bulk_action?: "select_all" | "clear_all";
      auto_patch_count?: number;
      reuse_rank?: number;
      visible_card_count?: number;
      card_list_state?: CardListState;
      card_save_state?: CardSaveState;
      saved_card_count?: number;
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

  function askForSlot(slot: { slot: string; prompt: string }) {
    track("ai_widget_slot_chip_click", "handoff_clarify", {
      clarify_slot: slot.slot,
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
    void ask(slot.prompt, activeSourceTypes);
  }

  function runClarifyAction(action: SearchHandoffClarifyAction, source: "handoff_clarify" | "quick_fix") {
    const nextPatch = mergeSearchPatch(handoffSearchPatch, action.searchPatch ?? {});
    const patchedContext = applySearchPatch(searchContext, nextPatch);
    const sourceTypes = action.recommendedSourceTypes && action.recommendedSourceTypes.length > 0
      ? action.recommendedSourceTypes
      : activeSourceTypes;
    setHandoffSearchPatch(nextPatch);
    track(
      source === "quick_fix" ? "ai_widget_quick_fix_click" : "ai_widget_clarify_action_click",
      source,
      {
        clarify_slot: action.slot,
        source_type_scope: sourceTypeScope(sourceTypes),
      },
    );
    void ask(action.prompt, sourceTypes, patchedContext);
  }

  function runQuickFix(slot: { slot: string; prompt: string }) {
    const mappedAction = handoffClarifyActions.find((action) => action.slot === slot.slot);
    if (mappedAction) {
      runClarifyAction(mappedAction, "quick_fix");
      return;
    }
    track("ai_widget_quick_fix_click", "quick_fix", {
      clarify_slot: slot.slot,
      source_type_scope: sourceTypeScope(activeSourceTypes),
    });
    void ask(slot.prompt, activeSourceTypes);
  }

  async function copyAnswerSummary() {
    if (!answer) {
      return;
    }
    const copied = await copyText(answer);
    if (!copied) {
      setError("클립보드 복사에 실패했습니다. 브라우저 권한을 확인해 주세요.");
      return;
    }
    setCopyDone(true);
    track("ai_widget_answer_copy_click", "results_cta", {
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

        <section className="concierge-slot-check">
          <div className="concierge-slot-head">
            <p className="concierge-slot-title">예약 정보 체크리스트</p>
            <p className="concierge-slot-rate">{slotCompletionRate}% 완료</p>
          </div>
          <div className="concierge-slot-bar">
            <span style={{ width: `${slotCompletionRate}%` }} />
          </div>
          <div className="concierge-slot-chips">
            {slotChips.map((slot) => (
              <button
                key={slot.slot}
                type="button"
                className={slot.missing ? "chip-btn slot-chip missing" : "chip-btn slot-chip done"}
                disabled={loading}
                onClick={() => askForSlot(slot)}
                title={slot.missing ? `${slot.label} 정보 보완` : `${slot.label} 다시 조정`}
              >
                {slot.missing ? `보완: ${slot.label}` : `완료: ${slot.label}`}
              </button>
            ))}
          </div>
        </section>

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
        {recentUserPrompts.length > 0 && (
          <section className="concierge-history-prompts">
            <p className="concierge-history-title">최근 요청 다시 쓰기</p>
            <div className="concierge-history-list">
              {recentUserPrompts.map((prompt, index) => (
                <button
                  key={`${prompt}-${index}`}
                  type="button"
                  className="chip-btn"
                  disabled={loading}
                  onClick={() => {
                    setMessage(prompt);
                    composerRef.current?.focus();
                    track("ai_widget_prompt_reuse_click", "prompt_history", {
                      source_type_scope: sourceTypeScope(activeSourceTypes),
                      reuse_rank: index + 1,
                    });
                  }}
                  title={prompt}
                >
                  {prompt.length > 24 ? `${prompt.slice(0, 24)}...` : prompt}
                </button>
              ))}
            </div>
          </section>
        )}

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
            {quickFixSlots.length > 0 && (
              <div className="concierge-handoff-quickfix">
                <p className="concierge-handoff-sort-title">빠른 보완</p>
                <div className="concierge-handoff-sort-options">
                  {quickFixSlots.map((slot) => (
                    <button
                      key={`quick-fix-${slot.slot}`}
                      type="button"
                      className="chip-btn"
                      disabled={loading}
                      onClick={() => runQuickFix(slot)}
                    >
                      {slot.label} 보완
                    </button>
                  ))}
                </div>
              </div>
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
                    onClick={() => runClarifyAction(action, "handoff_clarify")}
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
            <div className="concierge-handoff-tools">
              <button
                type="button"
                className="chip-btn"
                disabled={loading || bulkSelectableFilterTokens.length === 0 || areAllHandoffFiltersSelected}
                onClick={selectAllHandoffFilters}
              >
                필터 전체 선택
              </button>
              <button
                type="button"
                className="chip-btn"
                disabled={loading || selectedHandoffFilterKeys.length === 0}
                onClick={clearAllHandoffFilters}
              >
                필터 전체 해제
              </button>
            </div>
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
          {autopatchNotice && <p className="concierge-autopatch-note">{autopatchNotice}</p>}
          <div className="concierge-compose-actions">
            <button type="submit" disabled={!canSubmit}>{loading ? "생성 중..." : "추천 요청"}</button>
            {loading && (
              <button type="button" className="chip-btn concierge-stop" onClick={cancelGeneration}>
                생성 중단
              </button>
            )}
          </div>
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
            <>
              <p className="concierge-final-answer">
                <strong>요약</strong>
                <span>{answer}</span>
              </p>
              <div className="concierge-feedback-row">
                <button
                  type="button"
                  className={answerFeedback === "positive" ? "chip-btn active" : "chip-btn"}
                  onClick={() => submitAnswerFeedback("positive")}
                >
                  👍 도움됐어요
                </button>
                <button
                  type="button"
                  className={answerFeedback === "negative" ? "chip-btn active" : "chip-btn"}
                  onClick={() => submitAnswerFeedback("negative")}
                >
                  👎 아쉬워요
                </button>
                <button
                  type="button"
                  className="chip-btn"
                  disabled={!lastPrompt || loading}
                  onClick={regenerateAnswer}
                >
                  다시 추천
                </button>
                <button
                  type="button"
                  className={copyDone ? "chip-btn active" : "chip-btn"}
                  disabled={!answer}
                  onClick={copyAnswerSummary}
                >
                  {copyDone ? "요약 복사됨" : "요약 복사"}
                </button>
              </div>
            </>
          )}
        </div>

        {savedCards.length > 0 && (
          <section className="concierge-saved-panel">
            <div className="concierge-saved-head">
              <p className="concierge-saved-title">
                저장한 추천 ({visibleSavedCards.length}/{savedCards.length})
              </p>
              <button type="button" className="chip-btn" onClick={clearSavedCards}>
                저장 전체 해제
              </button>
            </div>
            <ul className="concierge-saved-list">
              {visibleSavedCards.slice(0, 8).map((card, index) => (
                <li key={`saved-${cardToken(card)}-${index}`}>
                  <p className="eyebrow">{card.type}</p>
                  <strong>{card.title}</strong>
                  <div className="concierge-card-actions">
                    <button type="button" className="chip-btn" disabled={loading} onClick={() => askCardFollowup(card, "saved_card")}>
                      유사 추천
                    </button>
                    {renderCardLink(card)}
                    <button type="button" className="chip-btn" onClick={() => toggleCardSave(card)}>
                      저장 해제
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}

        {cards.length > 0 && (
          <section className="concierge-cards-panel">
            <div className="concierge-card-filter-row">
              {availableCardTypes.map((type) => (
                <button
                  key={`card-type-${type}`}
                  type="button"
                  className={selectedCardType === type ? "chip-btn active" : "chip-btn"}
                  onClick={() => selectCardType(type)}
                >
                  {CARD_TYPE_FILTER_LABELS[type]} ({cardTypeCounts[type]})
                </button>
              ))}
            </div>
            <p className="concierge-card-filter-meta">표시 {visibleCards.length}개 / 전체 {cards.length}개</p>
            <ul className="concierge-card-list">
              {renderedCards.map((card, index) => (
                <li key={`${card.type}-${card.id ?? index}`}>
                  <p className="eyebrow">{card.type}</p>
                  <h3>{card.title}</h3>
                  {card.why && <p>{card.why}</p>}
                  <div className="concierge-card-actions">
                    <button
                      type="button"
                      className="chip-btn"
                      disabled={loading}
                      onClick={() => askCardFollowup(card, "results_card")}
                    >
                      유사 추천
                    </button>
                    <button
                      type="button"
                      className={savedCardKeys.has(cardToken(card)) ? "chip-btn active" : "chip-btn"}
                      onClick={() => toggleCardSave(card)}
                    >
                      {savedCardKeys.has(cardToken(card)) ? "저장됨" : "카드 저장"}
                    </button>
                    {renderCardLink(card)}
                  </div>
                </li>
              ))}
            </ul>
            {visibleCards.length > 4 && (
              <button type="button" className="chip-btn concierge-card-toggle" onClick={toggleCardList}>
                {expandedCards ? "추천 카드 접기" : `추천 카드 더보기 (${visibleCards.length - 4})`}
              </button>
            )}
          </section>
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

function buildCardFollowupPrompt(card: ChatCard, sourceType: CardTypeFilter, cityLabel: string, nights: number): string {
  const title = card.title.trim() || "추천 항목";
  switch (sourceType) {
    case "PROPERTY":
      return `${cityLabel} ${nights}박 일정 기준으로 ${title}와 비슷한 숙소 3곳을 장단점과 함께 비교 추천해줘`;
    case "PACKAGE":
      return `${cityLabel} 여행에서 ${title}와 비슷한 패키지 3개를 가격/포함사항 중심으로 추천해줘`;
    case "TICKET":
      return `${cityLabel} 여행에서 ${title}와 비슷한 티켓/액티비티 3개를 일정 맞춰 추천해줘`;
    case "POI":
      return `${cityLabel}에서 ${title} 주변으로 갈만한 명소와 맛집 동선을 추천해줘`;
    default:
      return `${cityLabel} 여행 추천을 다시 정리해줘`;
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

function patchFieldCount(patch: SearchHandoffSearchPatch): number {
  let count = 0;
  if (patch.city) {
    count += 1;
  }
  if (typeof patch.days === "number") {
    count += 1;
  }
  if (patch.companions) {
    count += 1;
  }
  return count;
}

function inferSearchPatchFromPrompt(message: string): SearchHandoffSearchPatch {
  const normalized = message.trim().toLowerCase();
  if (!normalized) {
    return {};
  }

  const patch: SearchHandoffSearchPatch = {};

  const matchedCityAlias = CITY_ALIAS_ENTRIES.find(([alias]) => normalized.includes(alias));
  if (matchedCityAlias) {
    const normalizedCity = normalizeCityCode(matchedCityAlias[0]);
    if (normalizedCity) {
      patch.city = normalizedCity;
    }
  }

  const stayPattern = normalized.match(/(\d+)\s*박\s*(\d+)\s*일/);
  if (stayPattern) {
    const days = Number(stayPattern[2]);
    if (Number.isFinite(days) && days >= 1 && days <= 30) {
      patch.days = Math.trunc(days);
    }
  } else {
    const nightsPattern = normalized.match(/(\d+)\s*박/);
    if (nightsPattern) {
      const nights = Number(nightsPattern[1]);
      if (Number.isFinite(nights) && nights >= 1 && nights <= 29) {
        patch.days = Math.trunc(nights) + 1;
      }
    } else {
      const daysPattern = normalized.match(/(\d+)\s*일/);
      if (daysPattern) {
        const days = Number(daysPattern[1]);
        if (Number.isFinite(days) && days >= 1 && days <= 30) {
          patch.days = Math.trunc(days);
        }
      }
    }
  }

  if (/(가족|아이|아동|부모님|키즈)/.test(normalized)) {
    patch.companions = "FAMILY";
  } else if (/(커플|연인|신혼|부부)/.test(normalized)) {
    patch.companions = "COUPLE";
  } else if (/(혼자|1인|나홀로|솔로)/.test(normalized)) {
    patch.companions = "SOLO";
  } else if (/(친구|우정|동행|단체)/.test(normalized)) {
    patch.companions = "FRIENDS";
  }

  return patch;
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
    lastPrompt: "",
    answerFeedback: null,
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
    savedCards: [],
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
    const isCard = (item: unknown): item is ChatCard => (
      !!item &&
      typeof item === "object" &&
      typeof (item as { type?: unknown }).type === "string" &&
      typeof (item as { title?: unknown }).title === "string"
    );
    return {
      ...defaults,
      open: parsed.open === true,
      activeSourceTypes,
      messageDraft: typeof parsed.messageDraft === "string" ? parsed.messageDraft : "",
      lastPrompt: typeof parsed.lastPrompt === "string" ? parsed.lastPrompt : "",
      answerFeedback: parsed.answerFeedback === "positive" || parsed.answerFeedback === "negative"
        ? parsed.answerFeedback
        : null,
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
      savedCards: Array.isArray(parsed.savedCards) ? parsed.savedCards.filter(isCard).slice(0, 12) : [],
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

async function recommend(payload: Record<string, unknown>, signal?: AbortSignal): Promise<ChatData> {
  const token = getAuthBearerToken();
  const response = await fetch(`${API_BASE}/v1/chat/recommend`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    signal,
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
  signal?: AbortSignal,
) {
  const token = getAuthBearerToken();
  const response = await fetch(`${API_BASE}/v1/chat/recommend:stream`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    signal,
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
  feedback_value?: "positive" | "negative";
  bulk_action?: "select_all" | "clear_all";
  auto_patch_count?: number;
  reuse_rank?: number;
  visible_card_count?: number;
  card_list_state?: CardListState;
  card_save_state?: CardSaveState;
  saved_card_count?: number;
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

async function copyText(value: string): Promise<boolean> {
  if (typeof navigator === "undefined") {
    return false;
  }

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return true;
    }
  } catch {
    // Fallback below
  }

  if (typeof document === "undefined") {
    return false;
  }

  try {
    const textArea = document.createElement("textarea");
    textArea.value = value;
    textArea.setAttribute("readonly", "true");
    textArea.style.position = "fixed";
    textArea.style.top = "-1000px";
    textArea.style.left = "-1000px";
    document.body.appendChild(textArea);
    textArea.select();
    const copied = document.execCommand("copy");
    document.body.removeChild(textArea);
    return copied;
  } catch {
    return false;
  }
}

function normalizeCardType(value: string | null | undefined): CardTypeFilter | null {
  const normalized = value?.trim().toUpperCase().replace("-", "_") ?? "";
  if (normalized === "PROPERTY" || normalized === "PACKAGE" || normalized === "TICKET" || normalized === "POI") {
    return normalized;
  }
  return null;
}

function cardToken(card: ChatCard): string {
  const normalizedType = normalizeCardType(card.type) ?? "UNKNOWN";
  const normalizedId = `${card.id ?? card.property_id ?? card.product_id ?? card.package_id ?? card.title}`.trim();
  return `${normalizedType}:${normalizedId}`;
}

function isAbortError(error: unknown): boolean {
  if (!error || typeof error !== "object") {
    return false;
  }
  const row = error as { name?: string };
  return row.name === "AbortError";
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

function slotClarifyPrompt(slot: string, searchContext: StaySearchInput): string {
  const city = searchContext.placeLabel || searchContext.city || "서울";
  switch (slot) {
    case "city":
      return "여행할 도시를 하나 정하고, 비슷한 대안 도시 2개도 알려줘.";
    case "days":
      return `${city} 여행 일정으로 1박/2박/3박 중 어떤 일정이 좋은지 추천해줘.`;
    case "companions":
      return `${city} 여행 동행을 혼자/커플/가족/친구 기준으로 비교해주고 최적 선택을 추천해줘.`;
    case "budget":
      return `${city} 기준 1박 예산을 10만원/20만원/30만원대로 나눠 추천해줘.`;
    case "preferences":
      return `${city} 숙소 추천에 중요한 옵션(조식, 주차, 취소정책, 수영장) 우선순위를 정리해줘.`;
    default:
      return `${city} 여행 조건을 더 구체적으로 정리해줘.`;
  }
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
