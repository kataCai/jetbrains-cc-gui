/* eslint-disable no-console */

import { PERF_TIMING } from '../constants/performance.js';
import { sendBridgeEvent } from './bridge.js';

// Vite exposes `import.meta.env.DEV` (boolean). In tests it may be undefined.
const DEBUG: boolean = (() => {
  try {
    const env = (import.meta as any)?.env;
    return Boolean(env?.DEV || env?.VITE_ENABLE_VCONSOLE === 'true');
  } catch {
    return false;
  }
})();

// Performance logging flag - enable to see timing info in console
const PERF_DEBUG = DEBUG; // Only enable in dev mode

interface FrontendDebugRuntimeConfig {
  panelEnabled: boolean;
  archiveEnabled: boolean;
  panelConfigured?: boolean;
  archiveConfigured?: boolean;
}

interface FrontendDiagnosticPayload {
  scope: string;
  message: string;
  details?: Record<string, unknown>;
}

const DIAGNOSTIC_MAX_STRING_LENGTH = 240;
const DIAGNOSTIC_MAX_DEPTH = 4;
const DIAGNOSTIC_MAX_ARRAY_ITEMS = 10;

const originalConsoleInfo = typeof console.info === 'function'
  ? console.info.bind(console)
  : () => {};

function readBooleanEnv(name: string): boolean {
  try {
    return (import.meta as any)?.env?.[name] === 'true';
  } catch {
    return false;
  }
}

const buildTimePanelEnabledDefault = (() => {
  try {
    const env = (import.meta as any)?.env;
    return Boolean(
      env?.DEV
      || env?.VITE_ENABLE_VCONSOLE === 'true'
      || env?.VITE_WEBVIEW_DEBUG === 'true'
    );
  } catch {
    return false;
  }
})();

const buildTimeArchiveEnabledDefault = readBooleanEnv('VITE_BRIDGE_DIAGNOSTIC_LOG');

let frontendDebugRuntimeConfig: Required<FrontendDebugRuntimeConfig> = {
  panelEnabled: buildTimePanelEnabledDefault,
  archiveEnabled: buildTimeArchiveEnabledDefault,
  panelConfigured: false,
  archiveConfigured: false,
};

/**
 * 更新前端诊断日志运行时配置。
 * 该配置由后端设置项驱动，要求在聊天页和设置页两个入口都能即时生效，
 * 因此统一保存在模块级状态中，避免依赖某个 React 组件常驻。
 *
 * @param nextConfig 最新的调试配置快照
 * @return 无返回值
 */
export function updateFrontendDebugRuntimeConfig(nextConfig: Partial<FrontendDebugRuntimeConfig>): void {
  const nextPanelConfigured = Object.prototype.hasOwnProperty.call(nextConfig, 'panelConfigured')
    ? nextConfig.panelConfigured === true
    : true;
  const nextArchiveConfigured = Object.prototype.hasOwnProperty.call(nextConfig, 'archiveConfigured')
    ? nextConfig.archiveConfigured === true
    : true;

  frontendDebugRuntimeConfig = {
    panelEnabled: nextPanelConfigured
      ? nextConfig.panelEnabled === true
      : buildTimePanelEnabledDefault,
    archiveEnabled: nextArchiveConfigured
      ? nextConfig.archiveEnabled === true
      : buildTimeArchiveEnabledDefault,
    panelConfigured: nextPanelConfigured,
    archiveConfigured: nextArchiveConfigured,
  };
}

function truncateDiagnosticString(value: string): string {
  if (value.length <= DIAGNOSTIC_MAX_STRING_LENGTH) {
    return value;
  }
  return `${value.slice(0, DIAGNOSTIC_MAX_STRING_LENGTH)}...[truncated ${value.length - DIAGNOSTIC_MAX_STRING_LENGTH} chars]`;
}

/**
 * 清洗诊断日志详情字段，避免敏感内容、超长文本或循环引用对象直接进入日志链路。
 * 这里额外维护 visited 集合，确保后续调用方误传自引用对象时不会无限展开，
 * 同时保留有限层级的结构摘要，方便排查 rich paste / history restore 问题。
 *
 * @param value 待清洗的任意详情值
 * @param depth 当前递归深度
 * @param visited 已访问对象集合，用于识别循环引用
 * @return 适合写入日志的安全值
 */
function sanitizeDiagnosticValue(value: unknown, depth = 0, visited = new WeakSet<object>()): unknown {
  if (value == null) {
    return value;
  }

  if (typeof value === 'string') {
    if (/^data:[^;]+;base64,/i.test(value)) {
      return '[omitted data-url]';
    }
    return truncateDiagnosticString(value);
  }

  if (
    typeof value === 'number'
    || typeof value === 'boolean'
    || typeof value === 'bigint'
  ) {
    return value;
  }

  if (depth >= DIAGNOSTIC_MAX_DEPTH) {
    return '[max-depth]';
  }

  if (typeof value === 'object') {
    if (visited.has(value)) {
      return '[circular]';
    }
    visited.add(value);
  }

  if (Array.isArray(value)) {
    const normalizedItems = value
      .slice(0, DIAGNOSTIC_MAX_ARRAY_ITEMS)
      .map((item) => sanitizeDiagnosticValue(item, depth + 1, visited));
    if (value.length > DIAGNOSTIC_MAX_ARRAY_ITEMS) {
      normalizedItems.push(`[truncated ${value.length - DIAGNOSTIC_MAX_ARRAY_ITEMS} items]`);
    }
    return normalizedItems;
  }

  if (typeof value === 'object') {
    const normalizedObject: Record<string, unknown> = {};
    Object.entries(value as Record<string, unknown>).forEach(([key, nestedValue]) => {
      normalizedObject[key] = sanitizeDiagnosticValue(nestedValue, depth + 1, visited);
    });
    return normalizedObject;
  }

  return String(value);
}

function sanitizeDiagnosticDetails(details?: Record<string, unknown>): Record<string, unknown> | undefined {
  if (!details) {
    return undefined;
  }
  return sanitizeDiagnosticValue(details) as Record<string, unknown>;
}

/**
 * 读取当前前端诊断日志运行时配置。
 * 仅用于测试与回调链路校验，业务日志输出应统一走 emitFrontendDiagnosticLog。
 *
 * @return 当前生效的调试配置快照
 */
export function getFrontendDebugRuntimeConfig(): FrontendDebugRuntimeConfig {
  return {
    panelEnabled: frontendDebugRuntimeConfig.panelEnabled,
    archiveEnabled: frontendDebugRuntimeConfig.archiveEnabled,
  };
}

/**
 * 发出受运行时配置控制的前端诊断日志。
 * panelEnabled 控制是否输出到前端调试面板；archiveEnabled 控制是否桥接回 Java 落入 idea.log。
 * 该链路独立于 console monkey-patch，避免分发包静音 console.info 后诊断日志整体失效。
 *
 * @param scope 诊断分类，例如 RichPaste.Apply / HistoryRestore.Frontend
 * @param message 诊断摘要
 * @param details 可选的结构化上下文
 * @return 无返回值
 */
export function emitFrontendDiagnosticLog(
  scope: string,
  message: string,
  details?: Record<string, unknown>,
): void {
  const shouldOutputToPanel = frontendDebugRuntimeConfig.panelEnabled;
  const shouldBridgeToArchive = frontendDebugRuntimeConfig.archiveEnabled;
  // 两个输出端都关闭时直接短路，避免日志工具在正常业务路径里先递归读取详情对象。
  if (!shouldOutputToPanel && !shouldBridgeToArchive) {
    return;
  }

  const sanitizedDetails = sanitizeDiagnosticDetails(details);
  const payload: FrontendDiagnosticPayload = {
    scope,
    message,
    ...(sanitizedDetails ? { details: sanitizedDetails } : {}),
  };

  if (shouldOutputToPanel) {
    originalConsoleInfo(`[FrontendDebug][${scope}]`, message, sanitizedDetails ?? {});
  }

  if (shouldBridgeToArchive) {
    sendBridgeEvent('frontend_debug_log', JSON.stringify(payload));
  }
}

export function debugLog(...args: unknown[]): void {
  if (!DEBUG) return;
  console.log(...args);
}

export function debugWarn(...args: unknown[]): void {
  if (!DEBUG) return;
  console.warn(...args);
}

export function debugError(...args: unknown[]): void {
  if (!DEBUG) return;
  console.error(...args);
}

/**
 * Performance timing utility for debugging slow operations
 * Usage:
 *   const timer = perfTimer('operationName');
 *   // ... do work ...
 *   timer.mark('step1');
 *   // ... do more work ...
 *   timer.end(); // logs total time and all marks
 */
export function perfTimer(name: string) {
  if (!PERF_DEBUG) {
    // Return no-op functions when disabled
    return {
      mark: () => {},
      end: () => {},
      log: () => {},
    };
  }

  const startTime = performance.now();
  const marks: Array<{ label: string; time: number }> = [];

  return {
    mark(label: string) {
      marks.push({ label, time: performance.now() - startTime });
    },
    end() {
      const totalTime = performance.now() - startTime;
      // Only log if operation took more than threshold (skip fast operations)
      if (totalTime > PERF_TIMING.MIN_LOG_THRESHOLD_MS) {
        const markStr = marks.map((m) => `${m.label}: ${m.time.toFixed(2)}ms`).join(', ');
        console.log(
          `%c[PERF] ${name}: ${totalTime.toFixed(2)}ms ${markStr ? `(${markStr})` : ''}`,
          totalTime > PERF_TIMING.SLOW_OPERATION_THRESHOLD_MS
            ? 'color: red; font-weight: bold'
            : 'color: orange'
        );
      }
      return totalTime;
    },
    log(message: string) {
      const elapsed = performance.now() - startTime;
      console.log(`%c[PERF] ${name} - ${message}: ${elapsed.toFixed(2)}ms`, 'color: gray');
    },
  };
}
