/**
 * 当前产品定义的任务提醒状态集合。
 * 这里既服务于设置页多选项，也作为配置归一化时的合法值白名单。
 */
export const TASK_REMINDER_STATES = [
  'waiting_confirm',
  'retrying',
  'recovered',
  'final_error',
  'completed',
] as const;

export type TaskReminderState = (typeof TASK_REMINDER_STATES)[number];
export type TaskReminderChannel = 'popup' | 'balloon' | 'sound';
export const POPUP_TASK_REMINDER_STATES = ['waiting_confirm', 'final_error'] as const;
export const TASK_REMINDER_CHANNEL_STATES: Record<TaskReminderChannel, readonly TaskReminderState[]> = {
  popup: POPUP_TASK_REMINDER_STATES,
  balloon: TASK_REMINDER_STATES,
  sound: TASK_REMINDER_STATES,
};

/**
 * 单个提醒渠道的通用配置。
 */
export interface TaskReminderChannelConfig {
  enabled: boolean;
  states: TaskReminderState[];
  onlyWhenIdeUnfocused: boolean;
}

/**
 * 声音提醒的专属配置。
 * 在通用渠道字段上，额外补充声音源选择信息。
 */
export interface TaskReminderSoundConfig extends TaskReminderChannelConfig {
  selectedSound: string;
  customSoundPath: string;
}

/**
 * 前端侧使用的 canonical task reminder 配置结构。
 */
export interface TaskReminderConfig {
  popup: TaskReminderChannelConfig;
  balloon: TaskReminderChannelConfig;
  sound: TaskReminderSoundConfig;
}

/**
 * 旧版声音配置结构。
 * 仅用于兼容旧桥接消息，不应再作为新的持久化模型继续扩展。
 */
export interface LegacySoundNotificationConfig {
  enabled?: boolean;
  states?: string[];
  onlyWhenIdeUnfocused?: boolean;
  onlyWhenUnfocused?: boolean;
  selectedSound?: string;
  customSoundPath?: string;
}

const DEFAULT_POPUP_STATES: TaskReminderState[] = ['waiting_confirm', 'final_error'];
const DEFAULT_BALLOON_STATES: TaskReminderState[] = ['completed', 'recovered', 'final_error'];
const DEFAULT_SOUND_STATES: TaskReminderState[] = ['completed'];

/**
 * 任务提醒的默认配置。
 * 这份默认值需要和后端 fallback 尽量保持一致，避免两端各自兜底后行为不同。
 */
export const DEFAULT_TASK_REMINDER_CONFIG: TaskReminderConfig = {
  popup: {
    enabled: true,
    // popup 只放最需要人工介入的状态，避免正常完成也强打断用户。
    states: DEFAULT_POPUP_STATES,
    onlyWhenIdeUnfocused: false,
  },
  balloon: {
    enabled: true,
    states: DEFAULT_BALLOON_STATES,
    onlyWhenIdeUnfocused: true,
  },
  sound: {
    enabled: true,
    states: DEFAULT_SOUND_STATES,
    onlyWhenIdeUnfocused: true,
    selectedSound: 'default',
    customSoundPath: '',
  },
};

/**
 * 判断某个值是否为合法的 TaskReminderState。
 */
const isReminderState = (value: unknown): value is TaskReminderState => (
  typeof value === 'string' && (TASK_REMINDER_STATES as readonly string[]).includes(value)
);

/**
 * 过滤并规范化状态数组。
 * 非法值会被剔除；如果结果为空，则回退到默认状态集合。
 */
const sanitizeStates = (
  states: unknown,
  fallback: TaskReminderState[],
  allowedStates: readonly TaskReminderState[] = TASK_REMINDER_STATES,
): TaskReminderState[] => {
  if (!Array.isArray(states)) return fallback.slice();
  const valid = states.filter((state): state is TaskReminderState => (
    isReminderState(state) && allowedStates.includes(state)
  ));
  // 去重后返回新数组，既保证 React state 可预测，也避免后端/本地存储重复项扩散。
  return valid.length > 0 ? Array.from(new Set(valid)) : fallback.slice();
};

/**
 * 读取布尔字段，非法值时回退到默认值。
 */
const toBoolean = (value: unknown, fallback: boolean): boolean => (
  typeof value === 'boolean' ? value : fallback
);

/**
 * 将任意来源的原始配置归一化为完整、可渲染的 task reminder 配置。
 */
export const normalizeTaskReminderConfig = (raw: unknown): TaskReminderConfig => {
  // 新旧设置来源都统一走这里归一化，确保 UI 层拿到的始终是完整结构，
  // 组件本身就不需要再为缺字段、空数组、非法类型到处写判空。
  const source = (raw && typeof raw === 'object') ? raw as Record<string, unknown> : {};
  const popupRaw = (source.popup && typeof source.popup === 'object') ? source.popup as Record<string, unknown> : {};
  const balloonRaw = (source.balloon && typeof source.balloon === 'object') ? source.balloon as Record<string, unknown> : {};
  const soundRaw = (source.sound && typeof source.sound === 'object') ? source.sound as Record<string, unknown> : {};

  const popupDefault = DEFAULT_TASK_REMINDER_CONFIG.popup;
  const balloonDefault = DEFAULT_TASK_REMINDER_CONFIG.balloon;
  const soundDefault = DEFAULT_TASK_REMINDER_CONFIG.sound;

  return {
    popup: {
      enabled: toBoolean(popupRaw.enabled, popupDefault.enabled),
      states: sanitizeStates(popupRaw.states, popupDefault.states, TASK_REMINDER_CHANNEL_STATES.popup),
      onlyWhenIdeUnfocused: toBoolean(
        popupRaw.onlyWhenIdeUnfocused,
        popupDefault.onlyWhenIdeUnfocused,
      ),
    },
    balloon: {
      enabled: toBoolean(balloonRaw.enabled, balloonDefault.enabled),
      states: sanitizeStates(balloonRaw.states, balloonDefault.states, TASK_REMINDER_CHANNEL_STATES.balloon),
      onlyWhenIdeUnfocused: toBoolean(
        balloonRaw.onlyWhenIdeUnfocused,
        balloonDefault.onlyWhenIdeUnfocused,
      ),
    },
    sound: {
      enabled: toBoolean(soundRaw.enabled, soundDefault.enabled),
      states: sanitizeStates(soundRaw.states, soundDefault.states, TASK_REMINDER_CHANNEL_STATES.sound),
      onlyWhenIdeUnfocused: toBoolean(
        soundRaw.onlyWhenIdeUnfocused,
        soundDefault.onlyWhenIdeUnfocused,
      ),
      selectedSound: typeof soundRaw.selectedSound === 'string'
        ? soundRaw.selectedSound
        : soundDefault.selectedSound,
      customSoundPath: typeof soundRaw.customSoundPath === 'string'
        ? soundRaw.customSoundPath
        : soundDefault.customSoundPath,
    },
  };
};

/**
 * 把旧版声音配置合并到 canonical 结构中。
 * 只允许影响 sound 子树，popup / balloon 永远以新结构为准。
 */
export const mergeLegacySoundConfig = (
  current: TaskReminderConfig,
  legacyRaw: unknown,
): TaskReminderConfig => {
  // 旧版 updateSoundNotificationConfig 只描述 sound 子树；
  // 这里明确只合并 sound，绝不反向覆盖 popup / balloon 的新配置。
  const legacy = (legacyRaw && typeof legacyRaw === 'object')
    ? legacyRaw as LegacySoundNotificationConfig
    : {};

  return {
    ...current,
    sound: {
      ...current.sound,
      enabled: typeof legacy.enabled === 'boolean' ? legacy.enabled : current.sound.enabled,
      states: sanitizeStates(legacy.states, current.sound.states),
      onlyWhenIdeUnfocused: typeof legacy.onlyWhenIdeUnfocused === 'boolean'
        ? legacy.onlyWhenIdeUnfocused
        : (typeof legacy.onlyWhenUnfocused === 'boolean'
            ? legacy.onlyWhenUnfocused
            : current.sound.onlyWhenIdeUnfocused),
      selectedSound: typeof legacy.selectedSound === 'string'
        ? legacy.selectedSound
        : current.sound.selectedSound,
      customSoundPath: typeof legacy.customSoundPath === 'string'
        ? legacy.customSoundPath
        : current.sound.customSoundPath,
    },
  };
};
