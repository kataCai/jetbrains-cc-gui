/**
 * Shared icon component that renders the correct vendor icon based on
 * provider ID and/or model ID.
 *
 * Replaces the duplicated ProviderIcon switch statements across:
 * - ModelSelect.tsx
 * - ProviderSelect.tsx
 * - BlinkingLogo/index.tsx
 * - HistoryView.tsx
 */
import ClaudeColor from '@lobehub/icons/es/Claude/components/Color';
import ClaudeMono from '@lobehub/icons/es/Claude/components/Mono';
import OpenAIMono from '@lobehub/icons/es/OpenAI/components/Mono';
import GeminiColor from '@lobehub/icons/es/Gemini/components/Color';
import GeminiMono from '@lobehub/icons/es/Gemini/components/Mono';
import QwenColor from '@lobehub/icons/es/Qwen/components/Color';
import QwenMono from '@lobehub/icons/es/Qwen/components/Mono';
import DeepSeekColor from '@lobehub/icons/es/DeepSeek/components/Color';
import DeepSeekMono from '@lobehub/icons/es/DeepSeek/components/Mono';
import KimiColor from '@lobehub/icons/es/Kimi/components/Color';
import KimiMono from '@lobehub/icons/es/Kimi/components/Mono';
import MoonshotMono from '@lobehub/icons/es/Moonshot/components/Mono';
import ZhipuColor from '@lobehub/icons/es/Zhipu/components/Color';
import ZhipuMono from '@lobehub/icons/es/Zhipu/components/Mono';
import MinimaxColor from '@lobehub/icons/es/Minimax/components/Color';
import MinimaxMono from '@lobehub/icons/es/Minimax/components/Mono';
import DoubaoColor from '@lobehub/icons/es/Doubao/components/Color';
import DoubaoMono from '@lobehub/icons/es/Doubao/components/Mono';
import SparkColor from '@lobehub/icons/es/Spark/components/Color';
import SparkMono from '@lobehub/icons/es/Spark/components/Mono';
import HunyuanColor from '@lobehub/icons/es/Hunyuan/components/Color';
import HunyuanMono from '@lobehub/icons/es/Hunyuan/components/Mono';
import BaichuanColor from '@lobehub/icons/es/Baichuan/components/Color';
import BaichuanMono from '@lobehub/icons/es/Baichuan/components/Mono';
import MistralColor from '@lobehub/icons/es/Mistral/components/Color';
import MistralMono from '@lobehub/icons/es/Mistral/components/Mono';
import MetaColor from '@lobehub/icons/es/Meta/components/Color';
import MetaMono from '@lobehub/icons/es/Meta/components/Mono';
import CohereColor from '@lobehub/icons/es/Cohere/components/Color';
import CohereMono from '@lobehub/icons/es/Cohere/components/Mono';
import GrokMono from '@lobehub/icons/es/Grok/components/Mono';
import OpenRouterMono from '@lobehub/icons/es/OpenRouter/components/Mono';
import YiColor from '@lobehub/icons/es/Yi/components/Color';
import YiMono from '@lobehub/icons/es/Yi/components/Mono';
import type { ReactElement } from 'react';
import { resolveIconVendor, type ModelVendor } from '../../utils/modelIconMapping';

export interface ProviderModelIconProps {
  /** Provider type: claude, codex, gemini, etc. */
  providerId?: string;
  /** Model ID for vendor-specific icon resolution (e.g. "qwen3.5-plus") */
  modelId?: string;
  /** Icon size in pixels */
  size?: number;
  /** Whether to use colored variant (true) or avatar/mono variant (false) */
  colored?: boolean;
}

/**
 * 生成 Xiaomi/MiMo 图标外层容器样式。
 *
 * 该容器同时用于彩色态和单色态，负责提供统一的尺寸、圆角和居中布局，
 * 避免 fallback 文本徽标在不同列表和按钮里出现对不齐的问题。
 *
 * @param size 图标目标尺寸，单位为像素。
 * @returns 可直接绑定到 React 元素的外层样式对象。
 */
function getXiaomiWrapperStyle(size: number): React.CSSProperties {
  return {
    alignItems: 'center',
    background: '#000',
    borderRadius: Math.max(3, Math.round(size * 0.22)),
    color: '#fff',
    display: 'inline-flex',
    flex: 'none',
    height: size,
    justifyContent: 'center',
    lineHeight: 1,
    width: size,
  };
}

/**
 * 生成 Xiaomi/MiMo fallback 文本徽标的文字样式。
 *
 * 当前依赖版本缺少 XiaomiMiMo 官方图标导出，因此这里通过文本徽标退化显示。
 * 文字尺寸与字重会随图标大小缩放，保证在紧凑列表与普通卡片里都具备可识别性。
 *
 * @param size 图标目标尺寸，单位为像素。
 * @returns 可直接绑定到 React 元素的文字样式对象。
 */
function getXiaomiLabelStyle(size: number): React.CSSProperties {
  return {
    color: '#fff',
    fontSize: Math.max(9, Math.round(size * 0.42)),
    fontWeight: 600,
    letterSpacing: '-0.02em',
    lineHeight: 1,
  };
}

/**
 * 渲染 Xiaomi/MiMo 的兼容图标。
 *
 * 当上游图标库缺失 XiaomiMiMo 组件时，这里退化为带背景的文本徽标，
 * 以保证构建稳定且不影响模型供应商识别。彩色态与单色态仅在背景色上区分，
 * 不再依赖不存在的第三方图标模块。
 *
 * @param size 图标目标尺寸，单位为像素。
 * @param colored 是否使用彩色态；`true` 为黑底，`false` 为灰底。
 * @returns Xiaomi/MiMo 图标对应的 React 元素。
 */
const XiaomiMiMoIcon = (size: number, colored: boolean): ReactElement => {
  return (
    <span
      aria-label="XiaomiMiMo"
      role="img"
      style={{
        ...getXiaomiWrapperStyle(size),
        background: colored ? '#000' : '#6b7280',
      }}
    >
      {
        // 当前 @lobehub/icons 版本没有 XiaomiMiMo 导出，这里退化为稳定的文字徽标，
        // 既避免主线构建失败，也保留 Xiaomi/MiMo 模型的可识别性。
      }
      <span style={getXiaomiLabelStyle(size)}>Mi</span>
    </span>
  );
};

/**
 * Icon renderers for each vendor.
 * Returns [coloredVersion, avatarVersion] JSX elements.
 */
const VENDOR_ICON_MAP: Record<
  ModelVendor,
  (size: number, colored: boolean) => ReactElement
> = {
  claude: (size, colored) =>
    colored ? <ClaudeColor size={size} /> : <ClaudeMono size={size} />,
  openai: (size, _colored) =>
    <OpenAIMono size={size} />,
  gemini: (size, colored) =>
    colored ? <GeminiColor size={size} /> : <GeminiMono size={size} />,
  qwen: (size, colored) =>
    colored ? <QwenColor size={size} /> : <QwenMono size={size} />,
  deepseek: (size, colored) =>
    colored ? <DeepSeekColor size={size} /> : <DeepSeekMono size={size} />,
  kimi: (size, colored) =>
    colored ? <KimiColor size={size} /> : <KimiMono size={size} />,
  moonshot: (size, _colored) =>
    <MoonshotMono size={size} />,
  zhipu: (size, colored) =>
    colored ? <ZhipuColor size={size} /> : <ZhipuMono size={size} />,
  minimax: (size, colored) =>
    colored ? <MinimaxColor size={size} /> : <MinimaxMono size={size} />,
  xiaomi: (size, colored) =>
    XiaomiMiMoIcon(size, colored),
  doubao: (size, colored) =>
    colored ? <DoubaoColor size={size} /> : <DoubaoMono size={size} />,
  spark: (size, colored) =>
    colored ? <SparkColor size={size} /> : <SparkMono size={size} />,
  hunyuan: (size, colored) =>
    colored ? <HunyuanColor size={size} /> : <HunyuanMono size={size} />,
  baichuan: (size, colored) =>
    colored ? <BaichuanColor size={size} /> : <BaichuanMono size={size} />,
  mistral: (size, colored) =>
    colored ? <MistralColor size={size} /> : <MistralMono size={size} />,
  meta: (size, colored) =>
    colored ? <MetaColor size={size} /> : <MetaMono size={size} />,
  cohere: (size, colored) =>
    colored ? <CohereColor size={size} /> : <CohereMono size={size} />,
  grok: (size, _colored) =>
    <GrokMono size={size} />,
  openrouter: (size, _colored) =>
    <OpenRouterMono size={size} />,
  yi: (size, colored) =>
    colored ? <YiColor size={size} /> : <YiMono size={size} />,
};

/**
 * Renders the appropriate vendor icon based on provider and model context.
 *
 * Resolution priority:
 * 1. modelId pattern match (most specific)
 * 2. providerId lookup
 * 3. Claude default
 */
export const ProviderModelIcon = ({
  providerId,
  modelId,
  size = 16,
  colored = false,
}: ProviderModelIconProps) => {
  const vendor = resolveIconVendor(providerId, modelId);
  const renderer = VENDOR_ICON_MAP[vendor];
  return renderer(size, colored);
};
