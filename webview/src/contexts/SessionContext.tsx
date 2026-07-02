import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { HistoryData } from '../types';

export interface SessionContextValue {
  currentSessionId: string | null;
  setCurrentSessionId: React.Dispatch<React.SetStateAction<string | null>>;
  customSessionTitle: string | null;
  setCustomSessionTitle: React.Dispatch<React.SetStateAction<string | null>>;
  logicalConversationId: string | null;
  setLogicalConversationId: React.Dispatch<React.SetStateAction<string | null>>;
  activeSegmentSessionId: string | null;
  setActiveSegmentSessionId: React.Dispatch<React.SetStateAction<string | null>>;
  parentSegmentSessionId: string | null;
  setParentSegmentSessionId: React.Dispatch<React.SetStateAction<string | null>>;
  continuationPending: boolean;
  setContinuationPending: React.Dispatch<React.SetStateAction<boolean>>;
  continuationSourceSessionId: string | null;
  setContinuationSourceSessionId: React.Dispatch<React.SetStateAction<string | null>>;
  historyData: HistoryData | null;
  setHistoryData: React.Dispatch<React.SetStateAction<HistoryData | null>>;
  /** Stale-closure guard: always reflects latest currentSessionId without triggering re-render. */
  currentSessionIdRef: React.RefObject<string | null>;
  /** Stale-closure guard: always reflects latest customSessionTitle without triggering re-render. */
  customSessionTitleRef: React.RefObject<string | null>;
  /** Stale-closure guard: always reflects latest logicalConversationId without triggering re-render. */
  logicalConversationIdRef: React.RefObject<string | null>;
  /** Stale-closure guard: always reflects latest activeSegmentSessionId without triggering re-render. */
  activeSegmentSessionIdRef: React.RefObject<string | null>;
  /** Stale-closure guard: always reflects latest continuationPending without triggering re-render. */
  continuationPendingRef: React.RefObject<boolean>;
}

const SessionContext = createContext<SessionContextValue | null>(null);

/**
 * Provides session-scoped state (current session id, custom title, history snapshot)
 * plus refs that track latest values for use inside long-lived event handlers.
 *
 * Stage 2 of TASK-P1-01.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [customSessionTitle, setCustomSessionTitle] = useState<string | null>(null);
  const [logicalConversationId, setLogicalConversationId] = useState<string | null>(null);
  const [activeSegmentSessionId, setActiveSegmentSessionId] = useState<string | null>(null);
  const [parentSegmentSessionId, setParentSegmentSessionId] = useState<string | null>(null);
  const [continuationPending, setContinuationPending] = useState(false);
  const [continuationSourceSessionId, setContinuationSourceSessionId] = useState<string | null>(null);
  const [historyData, setHistoryData] = useState<HistoryData | null>(null);

  const currentSessionIdRef = useRef<string | null>(currentSessionId);
  useEffect(() => { currentSessionIdRef.current = currentSessionId; }, [currentSessionId]);

  const customSessionTitleRef = useRef<string | null>(customSessionTitle);
  useEffect(() => { customSessionTitleRef.current = customSessionTitle; }, [customSessionTitle]);

  const logicalConversationIdRef = useRef<string | null>(logicalConversationId);
  useEffect(() => { logicalConversationIdRef.current = logicalConversationId; }, [logicalConversationId]);

  const activeSegmentSessionIdRef = useRef<string | null>(activeSegmentSessionId);
  useEffect(() => { activeSegmentSessionIdRef.current = activeSegmentSessionId; }, [activeSegmentSessionId]);

  const continuationPendingRef = useRef<boolean>(continuationPending);
  useEffect(() => { continuationPendingRef.current = continuationPending; }, [continuationPending]);

  const value = useMemo<SessionContextValue>(
    () => ({
      currentSessionId,
      setCurrentSessionId,
      customSessionTitle,
      setCustomSessionTitle,
      logicalConversationId,
      setLogicalConversationId,
      activeSegmentSessionId,
      setActiveSegmentSessionId,
      parentSegmentSessionId,
      setParentSegmentSessionId,
      continuationPending,
      setContinuationPending,
      continuationSourceSessionId,
      setContinuationSourceSessionId,
      historyData,
      setHistoryData,
      currentSessionIdRef,
      customSessionTitleRef,
      logicalConversationIdRef,
      activeSegmentSessionIdRef,
      continuationPendingRef,
    }),
    [
      currentSessionId,
      customSessionTitle,
      logicalConversationId,
      activeSegmentSessionId,
      parentSegmentSessionId,
      continuationPending,
      continuationSourceSessionId,
      historyData,
    ],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (ctx === null) {
    throw new Error('useSession must be used within a SessionProvider');
  }
  return ctx;
}

export { SessionContext };
