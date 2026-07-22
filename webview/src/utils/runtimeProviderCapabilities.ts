/**
 * Runtime provider capabilities subscriber registry.
 *
 * The Java bridge invokes a single set of `window.update*` callbacks to deliver
 * provider-list and active-provider updates. Multiple React consumers
 * (settings hooks, chat runtime selectors, etc.) need to react to those events.
 *
 * Registering a single dispatcher on `window` and routing events through a
 * subscriber Set keeps behavior deterministic regardless of mount order, and
 * avoids the previous "chain of overridden window callbacks" pattern, which
 * produced non-deterministic teardown when more than one consumer was alive.
 */

type ProviderListListener = (json: string) => void;
type ActiveProviderListener = (json: string) => void;
type CodexModelCatalogListener = (json: string) => void;

const providerListListeners = new Set<ProviderListListener>();
const activeProviderListeners = new Set<ActiveProviderListener>();
const codexProviderListListeners = new Set<ProviderListListener>();
const activeCodexProviderListeners = new Set<ActiveProviderListener>();
const codexModelCatalogListeners = new Set<CodexModelCatalogListener>();
// 统一目录可能先于具体 React 消费者挂载完成就被后端推送，因此这里缓存最近一次 payload，
// 确保设置页、聊天区等晚订阅者也能立即收到最新目录，而不是永远停留在初始 loading/旧数据。
let latestCodexModelCatalogPayload: string | null = null;

function emit<T>(listeners: Set<(value: T) => void>, value: T): void {
  // Snapshot to avoid mutation during iteration.
  Array.from(listeners).forEach((listener) => {
    try {
      listener(value);
    } catch (error) {
      console.error('[runtimeProviderCapabilities] Listener threw:', error);
    }
  });
}

/**
 * Installs (or re-installs) the single dispatcher on `window`. Safe to call
 * multiple times — calling it during a test reset, for example, simply
 * re-attaches the dispatcher.
 */
export function installRuntimeProviderDispatchers(): void {
  if (typeof window === 'undefined') {
    return;
  }

  window.updateProviders = (json: string) => {
    emit(providerListListeners, json);
  };

  window.updateActiveProvider = (json: string) => {
    emit(activeProviderListeners, json);
  };

  window.updateCodexProviders = (json: string) => {
    emit(codexProviderListListeners, json);
  };

  window.updateActiveCodexProvider = (json: string) => {
    emit(activeCodexProviderListeners, json);
  };

  window.updateCodexModelCatalog = (json: string) => {
    latestCodexModelCatalogPayload = json;
    emit(codexModelCatalogListeners, json);
  };
}

function ensureInstalled(): void {
  // The dispatcher is cheap to (re)install — make subscription self-bootstrapping
  // so that consumers do not depend on a separate bootstrap call.
  if (typeof window === 'undefined') return;
  if (window.updateProviders && window.updateActiveProvider
      && window.updateCodexProviders && window.updateActiveCodexProvider
      && window.updateCodexModelCatalog) {
    return;
  }
  installRuntimeProviderDispatchers();
}

export function subscribeProviderList(listener: ProviderListListener): () => void {
  ensureInstalled();
  providerListListeners.add(listener);
  return () => {
    providerListListeners.delete(listener);
  };
}

export function subscribeActiveProvider(listener: ActiveProviderListener): () => void {
  ensureInstalled();
  activeProviderListeners.add(listener);
  return () => {
    activeProviderListeners.delete(listener);
  };
}

export function subscribeCodexProviderList(listener: ProviderListListener): () => void {
  ensureInstalled();
  codexProviderListListeners.add(listener);
  return () => {
    codexProviderListListeners.delete(listener);
  };
}

export function subscribeActiveCodexProvider(listener: ActiveProviderListener): () => void {
  ensureInstalled();
  activeCodexProviderListeners.add(listener);
  return () => {
    activeCodexProviderListeners.delete(listener);
  };
}

/**
 * 订阅后端回推的 Codex 统一模型目录。
 * 聊天区与设置页后续都应共享这一份 catalog，避免继续各自拼接 provider/models。
 *
 * @param listener 接收原始 JSON 字符串的监听器
 * @return 取消订阅函数
 */
export function subscribeCodexModelCatalog(listener: CodexModelCatalogListener): () => void {
  ensureInstalled();
  codexModelCatalogListeners.add(listener);
  if (latestCodexModelCatalogPayload !== null) {
    listener(latestCodexModelCatalogPayload);
  }
  return () => {
    codexModelCatalogListeners.delete(listener);
  };
}

/**
 * 仅供测试使用：清理 dispatcher 缓存与 window 桥接回调。
 * 统一目录现在支持“最新 payload 回放”，测试若不显式重置会互相污染。
 */
export function resetRuntimeProviderCapabilitiesForTest(): void {
  providerListListeners.clear();
  activeProviderListeners.clear();
  codexProviderListListeners.clear();
  activeCodexProviderListeners.clear();
  codexModelCatalogListeners.clear();
  latestCodexModelCatalogPayload = null;
  if (typeof window === 'undefined') {
    return;
  }
  window.updateProviders = undefined;
  window.updateActiveProvider = undefined;
  window.updateCodexProviders = undefined;
  window.updateActiveCodexProvider = undefined;
  window.updateCodexModelCatalog = undefined;
}

// 模块一旦被任意消费者导入，就先安装统一 dispatcher。
// 这样即使后端比 React effect 更早推送目录，也会先进入共享缓存，再由晚订阅者回放消费。
if (typeof window !== 'undefined') {
  installRuntimeProviderDispatchers();
}
