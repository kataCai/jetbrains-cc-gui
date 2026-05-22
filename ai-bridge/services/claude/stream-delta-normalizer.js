/**
 * 获取指定 block 类型的内容索引表。
 * text 与 thinking 各自独立维护，避免不同 block 之间互相污染。
 *
 * @param {object} turnState 当前 turn 状态
 * @param {string} key turnState 中对应的 Map 字段名
 * @returns {Map<number, string>}
 */
function getBlockMap(turnState, key) {
  if (!(turnState[key] instanceof Map)) {
    turnState[key] = new Map();
  }
  return turnState[key];
}

/**
 * 统一 block index，缺省或非法时回退到 0。
 *
 * @param {number|string|undefined|null} index block 索引
 * @returns {number}
 */
function getBlockIndex(index) {
  const numericIndex = typeof index === 'string' ? Number(index) : index;
  return Number.isInteger(numericIndex) && numericIndex >= 0 ? numericIndex : 0;
}

/**
 * 获取每个 block 的流式模式索引表。
 * incremental 表示标准增量流，snapshot 表示累计快照流。
 *
 * @param {object} turnState 当前 turn 状态
 * @returns {Map<string, 'incremental'|'snapshot'>}
 */
function getModeMap(turnState) {
  if (!(turnState.blockStreamModeByKey instanceof Map)) {
    turnState.blockStreamModeByKey = new Map();
  }
  return turnState.blockStreamModeByKey;
}

/**
 * 生成 block 的唯一 key。
 *
 * @param {'text'|'thinking'} kind block 类型
 * @param {number} blockIndex block 索引
 * @returns {string}
 */
function modeKey(kind, blockIndex) {
  return `${kind}:${blockIndex}`;
}

/**
 * 计算当前输入相对上次内容的真正新增片段。
 * 兼容标准增量流、累计快照流和 corrective snapshot 覆写场景。
 *
 * @param {string} previous 上次累计内容
 * @param {string} incoming 本次输入内容
 * @param {'incremental'|'snapshot'|undefined} mode 当前 block 的流模式
 * @returns {{ novel: string, next: string, mode: 'incremental'|'snapshot'|undefined }}
 */
function computeNovelDelta(previous, incoming, mode) {
  if (!incoming) {
    return { novel: '', next: previous, mode };
  }
  if (!previous) {
    return { novel: incoming, next: incoming, mode };
  }

  // 累计快照：incoming = previous + suffix，并锁定后续 corrective snapshot 处理模式。
  if (incoming.startsWith(previous)) {
    return { novel: incoming.slice(previous.length), next: incoming, mode: 'snapshot' };
  }

  // 旧快照回放：incoming 已被 previous 覆盖时不再重复输出。
  if (previous.startsWith(incoming) || previous.endsWith(incoming)) {
    return { novel: '', next: previous, mode };
  }

  // 已确认是 snapshot 模式后，分叉内容通常是模型修正文案，应静默吸收避免重复渲染。
  if (mode === 'snapshot') {
    return { novel: '', next: incoming, mode };
  }

  // 默认按 Anthropic 标准增量流处理。
  return { novel: incoming, next: previous + incoming, mode: 'incremental' };
}

/**
 * 对单个 block 的流式增量进行归一化。
 *
 * @param {object} turnState 当前 turn 状态
 * @param {'text'|'thinking'} kind block 类型
 * @param {number|string|undefined|null} index block 索引
 * @param {string} incoming 本次输入内容
 * @returns {string} 真正需要输出给前端的新增片段
 */
export function normalizeStreamDelta(turnState, kind, index, incoming) {
  const text = typeof incoming === 'string' ? incoming : '';
  const key = kind === 'thinking' ? 'thinkingBlockContentByIndex' : 'textBlockContentByIndex';
  const blockMap = getBlockMap(turnState, key);
  const blockIndex = getBlockIndex(index);
  const previous = blockMap.get(blockIndex) || '';

  const modeMap = getModeMap(turnState);
  const mKey = modeKey(kind, blockIndex);
  const mode = modeMap.get(mKey);

  const result = computeNovelDelta(previous, text, mode);
  blockMap.set(blockIndex, result.next);
  if (result.mode && result.mode !== mode) {
    modeMap.set(mKey, result.mode);
  }
  return result.novel;
}

/**
 * 记录 snapshot 内容，用于后续 delta 去重与 corrective snapshot 识别。
 *
 * @param {object} turnState 当前 turn 状态
 * @param {'text'|'thinking'} kind block 类型
 * @param {number|string|undefined|null} index block 索引
 * @param {string} snapshot snapshot 内容
 * @returns {void}
 */
export function rememberStreamSnapshot(turnState, kind, index, snapshot) {
  const text = typeof snapshot === 'string' ? snapshot : '';
  const key = kind === 'thinking' ? 'thinkingBlockContentByIndex' : 'textBlockContentByIndex';
  const blockMap = getBlockMap(turnState, key);
  const blockIndex = getBlockIndex(index);
  const previous = blockMap.get(blockIndex) || '';
  if (text.length >= previous.length) {
    blockMap.set(blockIndex, text);
  }

  // snapshot 扩展旧内容时锁定模式，后续分叉 rewrite 不再误判为新增 delta。
  if (text.length > 0 && previous.length > 0 && text.startsWith(previous) && text !== previous) {
    const modeMap = getModeMap(turnState);
    modeMap.set(modeKey(kind, blockIndex), 'snapshot');
  }
}
