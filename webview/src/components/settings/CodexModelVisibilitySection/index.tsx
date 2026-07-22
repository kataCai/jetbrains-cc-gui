import { useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CodexModelCatalogItem,
  CodexModelVisibilityConfig,
} from '../../../types/provider';
import styles from './style.module.less';

const DEFAULT_PREVIEW_COUNT = 6;

interface CodexModelVisibilitySectionProps {
  catalog: CodexModelCatalogItem[];
  loading: boolean;
  onRefresh: () => void;
  onSaveVisibility: (visibilityConfig: CodexModelVisibilityConfig) => void;
  onDeleteCatalogItem?: (catalogItem: CodexModelCatalogItem) => void;
  showHeader?: boolean;
}

/**
 * 供应商分组视图模型。
 * 该结构只服务于前端展示层，把同一供应商下的模型归并到同一张二级卡片中，
 * 避免设置页继续使用跨供应商平铺列表造成阅读混乱。
 */
interface ProviderModelGroup {
  providerId: string;
  providerName: string;
  items: CodexModelCatalogItem[];
}

/**
 * 供应商分组作用范围快照。
 * 该结构把“当前组头应统计和控制的完整模型集合”显式化，
 * 避免默认折叠态下错误地只基于当前渲染子集计算总开关和数量徽标。
 */
interface ProviderScopeState {
  items: CodexModelCatalogItem[];
  keys: ReadonlySet<string>;
}

/**
 * 计算单个 provider 分组在折叠态下应优先展示的模型子集。
 * 预览规则下沉到组内：优先展示该 provider 下 `visible=true` 的模型；
 * 若该 provider 当前没有任何可见模型，则回退展示该 provider 的前 N 项，
 * 避免“当前无可见模型的 provider”在默认态整组消失。
 *
 * @param items 当前 provider 分组下的完整模型列表。
 * @return 折叠态下应该优先展示的模型列表。
 */
function buildProviderPreviewItems(items: CodexModelCatalogItem[]): CodexModelCatalogItem[] {
  const visibleItems = items.filter((item) => item.visible);
  if (visibleItems.length > 0) {
    return visibleItems.slice(0, DEFAULT_PREVIEW_COUNT);
  }
  return items.slice(0, DEFAULT_PREVIEW_COUNT);
}

/**
 * 生成单模型切换后的完整可见性映射，满足后端“全量保存”协议要求。
 * 即使界面上只切换了一个模型，也必须把当前完整目录里的所有 key 一并回传，
 * 避免后端把未出现的目录项误判成“应删除”或“应丢失”的历史配置。
 *
 * @param catalog 当前完整模型目录。
 * @param targetKey 本次被切换的模型目录 key。
 * @return 可直接提交给保存接口的完整可见性配置。
 */
function buildNextVisibilityConfig(
  catalog: CodexModelCatalogItem[],
  targetKey: string,
): CodexModelVisibilityConfig {
  return catalog.reduce<CodexModelVisibilityConfig>((result, item) => {
    result[item.key] = {
      visible: item.key === targetKey ? !item.visible : item.visible,
    };
    return result;
  }, {});
}

/**
 * 按供应商对模型目录做稳定分组。
 * 分组顺序严格沿用当前目录顺序，避免在搜索、展开和折叠时发生供应商卡片跳动。
 *
 * @param items 当前需要展示的模型项列表。
 * @return 供应商分组后的二级卡片数据。
 */
function groupCatalogByProvider(items: CodexModelCatalogItem[]): ProviderModelGroup[] {
  const groupMap = new Map<string, ProviderModelGroup>();
  items.forEach((item) => {
    const existingGroup = groupMap.get(item.providerId);
    if (existingGroup) {
      existingGroup.items.push(item);
      return;
    }
    groupMap.set(item.providerId, {
      providerId: item.providerId,
      providerName: item.providerName,
      items: [item],
    });
  });
  return Array.from(groupMap.values()).map((group) => ({
    ...group,
    items: sortGroupItemsVisibleFirst(group.items),
  }));
}

/**
 * 对单个供应商分组内的模型做“visible first”稳定分区排序。
 * 这里不能直接调用原地 `sort`，否则相同可见性分区内的原始顺序可能被打乱，
 * 导致用户在刷新、搜索或展开时感知到列表跳动。
 *
 * @param items 当前供应商分组下的模型项。
 * @return 已按“可见在前、各分区内部顺序稳定”重排后的模型数组。
 */
function sortGroupItemsVisibleFirst(items: CodexModelCatalogItem[]): CodexModelCatalogItem[] {
  const visibleItems: CodexModelCatalogItem[] = [];
  const hiddenItems: CodexModelCatalogItem[] = [];
  items.forEach((item) => {
    if (item.visible) {
      visibleItems.push(item);
      return;
    }
    hiddenItems.push(item);
  });
  return [...visibleItems, ...hiddenItems];
}

/**
 * 计算某个供应商分组当前是否处于“全部可见”状态。
 * 只要当前作用范围内有一个模型不可见，就把该分组视为未全选，
 * 这样总开关的语义可以稳定落在“全选 / 全不选”两态上，而不引入三态复杂度。
 *
 * @param items 当前分组在当前展示作用范围内的模型列表。
 * @return `true` 表示当前分组已全部可见。
 */
function areAllGroupItemsVisible(items: CodexModelCatalogItem[]): boolean {
  return items.length > 0 && items.every((item) => item.visible);
}

/**
 * 生成供应商级批量切换后的完整可见性映射。
 * 搜索态下只影响当前匹配到的子集；非搜索态下则作用于当前分组在完整目录中的全部模型。
 * 无论作用范围如何变化，最终都必须返回完整 config，保证后端保存协议不变。
 *
 * @param catalog 当前完整模型目录。
 * @param targetKeys 本次允许被组级总开关影响的模型 key 集合。
 * @param nextVisible 目标可见性状态，`true` 表示批量全选，`false` 表示批量全不选。
 * @return 可直接提交给保存接口的完整可见性配置。
 */
function buildGroupVisibilityConfig(
  catalog: CodexModelCatalogItem[],
  targetKeys: ReadonlySet<string>,
  nextVisible: boolean,
): CodexModelVisibilityConfig {
  return catalog.reduce<CodexModelVisibilityConfig>((result, item) => {
    result[item.key] = {
      visible: targetKeys.has(item.key) ? nextVisible : item.visible,
    };
    return result;
  }, {});
}

/**
 * Codex 模型展示设置卡片。
 * 该组件只消费后端聚合后的统一模型目录，并在前端完成：
 * 1. 搜索过滤；
 * 2. 按 provider 分组展示，并在每个分组内维护折叠预览；
 * 3. 单模型可见性开关；
 * 4. 供应商级“全选 / 全不选”总开关；
 * 5. 不可运行模型的授权提示。
 *
 * 展示约束：
 * 1. 默认态必须展示当前过滤结果下的全部 provider 分组，不允许因某个 provider 无可见模型而整组消失；
 * 2. 展开/收起仅作用于当前 provider 分组，不再提供全局“查看全部模型”入口。
 *
 * @param catalog 后端返回的统一模型目录。
 * @param loading 当前目录是否正在加载。
 * @param onRefresh 重新拉取模型目录。
 * @param onSaveVisibility 保存完整可见性映射。
 * @param showHeader 是否显示组件自身标题；嵌入上层分组时可关闭。
 * @return Codex 模型展示设置区域。
 */
export default function CodexModelVisibilitySection({
  catalog,
  loading,
  onRefresh,
  onSaveVisibility,
  onDeleteCatalogItem,
  showHeader = true,
}: CodexModelVisibilitySectionProps) {
  const { t } = useTranslation();
  const [keyword, setKeyword] = useState('');
  const [expandedProviderIds, setExpandedProviderIds] = useState<ReadonlySet<string>>(new Set());

  /**
   * 搜索只作用于展示层，不改写原始目录。
   * 这样切换搜索词不会污染可见性状态，也便于后续继续叠加来源筛选等交互。
   */
  const filteredCatalog = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    if (!normalizedKeyword) {
      return catalog;
    }
    return catalog.filter((item) => {
      const haystack = [
        item.label,
        item.modelId,
        item.providerName,
        item.providerId,
        item.description,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return haystack.includes(normalizedKeyword);
    });
  }, [catalog, keyword]);

  const isSearching = keyword.trim().length > 0;

  /**
   * 基于完整过滤结果构建全部 provider 分组。
   * 默认态必须先完整分组，再在每个分组内单独计算预览子集，
   * 避免“先全局裁剪再分组”导致某些 provider 整组消失。
   */
  const fullProviderGroups = useMemo(
    () => groupCatalogByProvider(filteredCatalog),
    [filteredCatalog],
  );

  /**
   * 计算每个 provider 在“完整目录视角”下的模型集合。
   * 搜索态下直接展示完整匹配结果；非搜索态下用于：
   * 1. 组头数量徽标；
   * 2. 组级展开/收起判断；
   * 3. 展开后补齐当前 provider 的完整模型列表。
   */
  const fullGroupMap = useMemo(() => {
    return fullProviderGroups.reduce<Map<string, ProviderModelGroup>>((result, group) => {
      result.set(group.providerId, group);
      return result;
    }, new Map());
  }, [fullProviderGroups]);

  /**
   * 渲染层最终使用的 provider 分组。
   * 搜索态：直接展示每个 provider 的全部匹配结果；
   * 默认态：每个 provider 独立维护折叠预览，展开后仅补齐当前分组。
   */
  const displayedGroups = useMemo(() => {
    if (isSearching) {
      return fullProviderGroups;
    }
    return fullProviderGroups.map((group) => {
      if (expandedProviderIds.has(group.providerId)) {
        return group;
      }
      return {
        ...group,
        items: buildProviderPreviewItems(group.items),
      };
    });
  }, [expandedProviderIds, fullProviderGroups, isSearching]);

  /**
   * 为供应商级总开关准备作用范围映射。
   * 非搜索态下作用于完整目录中的同 provider 全部模型；
   * 搜索态下只作用于当前匹配结果里的同 provider 子集，避免误改未匹配模型。
   */
  const groupScopeStateMap = useMemo(() => {
    return displayedGroups.reduce<Map<string, ProviderScopeState>>((result, group) => {
      const scopedItems = isSearching
        ? group.items
        : catalog.filter((item) => item.providerId === group.providerId);
      result.set(group.providerId, {
        items: scopedItems,
        keys: new Set(scopedItems.map((item) => item.key)),
      });
      return result;
    }, new Map());
  }, [catalog, displayedGroups, isSearching]);

  /**
   * 切换单模型可见性时始终按完整目录生成全量配置。
   * 这样 UI 层无论处于折叠态、搜索态还是展开态，都不会破坏保存协议。
   *
   * @param targetKey 被切换的目录项 key。
   */
  const handleToggleVisibility = (targetKey: string) => {
    onSaveVisibility(buildNextVisibilityConfig(catalog, targetKey));
  };

  /**
   * 切换供应商级“全选 / 全不选”总开关。
   * 搜索态下只影响当前关键字匹配到的该供应商子集；其他场景则作用于该供应商所有模型。
   *
   * @param providerId 当前操作的供应商 id。
   * @param groupItems 当前展示分组下的模型项，用于判断当前是否已全选。
   */
  const handleToggleGroupVisibility = (providerId: string, groupItems: CodexModelCatalogItem[]) => {
    const scopedState = groupScopeStateMap.get(providerId);
    if (!scopedState || scopedState.keys.size === 0) {
      return;
    }
    const nextVisible = !areAllGroupItemsVisible(groupItems);
    onSaveVisibility(buildGroupVisibilityConfig(catalog, scopedState.keys, nextVisible));
  };

  /**
   * 独立切换单个 provider 分组的展开状态。
   * 该入口只作用于当前 provider，不会影响其他分组的折叠预览。
   *
   * @param providerId 当前需要展开或收起的 provider id
   */
  const handleToggleProviderExpansion = (providerId: string) => {
    setExpandedProviderIds((current) => {
      const next = new Set(current);
      if (next.has(providerId)) {
        next.delete(providerId);
      } else {
        next.add(providerId);
      }
      return next;
    });
  };

  /**
   * 阻止组内操作区点击冒泡到组头折叠交互。
   * 这样点击组级总开关、删除按钮等控件时，只会执行各自操作，不会误触发展开/收起。
   *
   * @param event 当前点击或按键事件
   */
  const stopProviderHeaderToggle = (
    event: Pick<MouseEvent<HTMLElement>, 'stopPropagation'>
      | Pick<KeyboardEvent<HTMLElement>, 'stopPropagation'>,
  ) => {
    event.stopPropagation();
  };

  return (
    <section className={styles.sectionCard}>
      {showHeader && (
        <div className={styles.header}>
          <div className={styles.headerText}>
            <h4 className={styles.title}>{t('settings.codexProvider.modelsTitle')}</h4>
            <p className={styles.description}>{t('settings.codexProvider.modelsDescription')}</p>
          </div>
        </div>
      )}

      <div className={styles.toolbar}>
        <input
          className={styles.searchInput}
          type="text"
          value={keyword}
          placeholder={t('settings.codexProvider.modelsSearchPlaceholder')}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <button
          type="button"
          className={styles.refreshButton}
          onClick={onRefresh}
        >
          {t('common.refresh')}
        </button>
      </div>

      {loading ? (
        <div className={styles.statePanel}>{t('common.loading')}</div>
      ) : filteredCatalog.length === 0 ? (
        <div className={styles.statePanel}>{t('settings.codexProvider.modelsEmpty')}</div>
      ) : (
        <div className={styles.catalogLayout}>
          <div className={styles.providerGroupList}>
            {displayedGroups.map((group) => {
              const scopedState = groupScopeStateMap.get(group.providerId);
              const scopedItems = scopedState?.items ?? group.items;
              const isGroupFullyVisible = areAllGroupItemsVisible(scopedItems);
              const fullGroupItems = fullGroupMap.get(group.providerId)?.items ?? group.items;
              const previewItemCount = buildProviderPreviewItems(fullGroupItems).length;
              const fullItemCount = fullGroupItems.length;
              // 仅当该 provider 在折叠预览外还有更多模型时，才提供组级展开入口。
              const canExpandProviderGroup = !isSearching && fullItemCount > previewItemCount;
              const isProviderGroupExpanded = expandedProviderIds.has(group.providerId);
              return (
                <section
                  key={group.providerId}
                  className={styles.providerGroupCard}
                  data-testid={`provider-group:${group.providerId}`}
                >
                  <div
                    className={`${styles.providerGroupHeader} ${canExpandProviderGroup ? styles.providerGroupHeaderExpandable : ''}`}
                    role={canExpandProviderGroup ? 'button' : undefined}
                    tabIndex={canExpandProviderGroup ? 0 : undefined}
                    aria-label={canExpandProviderGroup ? `toggle-provider-group:${group.providerId}` : undefined}
                    aria-expanded={canExpandProviderGroup ? isProviderGroupExpanded : undefined}
                    onClick={canExpandProviderGroup ? () => handleToggleProviderExpansion(group.providerId) : undefined}
                    onKeyDown={canExpandProviderGroup
                      ? (event) => {
                        if (event.key !== 'Enter' && event.key !== ' ') {
                          return;
                        }
                        event.preventDefault();
                        handleToggleProviderExpansion(group.providerId);
                      }
                      : undefined}
                  >
                    <div className={styles.providerGroupTitleRow}>
                      {canExpandProviderGroup && (
                        <span className={styles.providerGroupChevron} aria-hidden="true">
                          <span className={`codicon ${isProviderGroupExpanded ? 'codicon-chevron-down' : 'codicon-chevron-right'}`} />
                        </span>
                      )}
                      <h5 className={styles.providerGroupTitle}>{group.providerName}</h5>
                      <span className={styles.providerGroupBadge}>
                        {t('settings.codexProvider.modelsGroupCount', { count: scopedItems.length })}
                      </span>
                      {canExpandProviderGroup && (
                        <span className={styles.providerGroupExpandHint}>
                          {isProviderGroupExpanded
                            ? t('settings.codexProvider.modelsCollapse')
                            : t('settings.codexProvider.modelsViewAll')}
                        </span>
                      )}
                    </div>

                    <div
                      className={styles.providerGroupActions}
                      onClick={stopProviderHeaderToggle}
                      onKeyDown={stopProviderHeaderToggle}
                    >
                      <span className={styles.providerGroupActionLabel}>
                        {isGroupFullyVisible
                          ? t('settings.codexProvider.modelsGroupDeselectAll')
                          : t('settings.codexProvider.modelsGroupSelectAll')}
                      </span>
                      <label className={styles.toggleSwitch}>
                        <input
                          className={styles.toggleInput}
                          type="checkbox"
                          role="switch"
                          aria-label={`toggle-group:${group.providerId}`}
                          checked={isGroupFullyVisible}
                          onChange={() => handleToggleGroupVisibility(group.providerId, scopedItems)}
                        />
                        <span className={styles.toggleSlider} />
                      </label>
                    </div>
                  </div>

                  <div className={styles.providerGroupModels}>
                    {group.items.map((item) => (
                      <article key={item.key} className={styles.modelCard}>
                        <div className={styles.modelInfo}>
                          <div className={styles.modelTopRow}>
                            <h5 className={styles.modelName}>{item.label}</h5>
                            <span className={styles.sourceBadge}>
                              {t(`settings.codexProvider.modelsSource.${item.source}`)}
                            </span>
                          </div>
                          <div className={styles.providerName}>{item.providerName}</div>
                          {item.description && (
                            <p className={styles.modelDescription}>{item.description}</p>
                          )}
                          {!item.runnable && (
                            <span className={styles.unavailableBadge}>
                              {t('settings.codexProvider.modelsUnavailable')}
                            </span>
                          )}
                        </div>

                        <div className={styles.modelActions}>
                          {onDeleteCatalogItem && (
                            <button
                              type="button"
                              className={styles.deleteButton}
                              aria-label={`delete:${item.key}`}
                              title={t('common.delete')}
                              onClick={() => onDeleteCatalogItem(item)}
                            >
                              <span className="codicon codicon-trash" />
                            </button>
                          )}
                          <label className={styles.toggleSwitch}>
                            <input
                              className={styles.toggleInput}
                              type="checkbox"
                              role="switch"
                              aria-label={`toggle:${item.key}`}
                              checked={item.visible}
                              onChange={() => handleToggleVisibility(item.key)}
                            />
                            <span className={styles.toggleSlider} />
                          </label>
                        </div>
                      </article>
                    ))}
                  </div>
                </section>
              );
            })}
          </div>

        </div>
      )}
    </section>
  );
}
