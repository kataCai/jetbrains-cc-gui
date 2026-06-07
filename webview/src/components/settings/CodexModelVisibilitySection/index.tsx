import { useMemo, useState } from 'react';
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
  showHeader?: boolean;
}

/**
 * 计算设置页默认折叠态需要优先展示的模型子集。
 * 默认优先展示当前已可见模型；如果用户还没有开启任何模型，则回退到目录前 N 项，
 * 避免面板在初始态出现空白区域，同时继续保持列表长度可控。
 *
 * @param catalog 当前用于展示的模型目录。
 * @return 折叠态下应该优先展示的模型列表。
 */
function buildPreviewCatalog(catalog: CodexModelCatalogItem[]): CodexModelCatalogItem[] {
  const visibleCatalog = catalog.filter((item) => item.visible);
  if (visibleCatalog.length > 0) {
    return visibleCatalog.slice(0, DEFAULT_PREVIEW_COUNT);
  }
  return catalog.slice(0, DEFAULT_PREVIEW_COUNT);
}

/**
 * 生成完整的模型可见性映射，满足后端“全量保存”的协议要求。
 * 即使界面上只切换了一个模型，也必须把当前目录里的所有 key 一并回传，
 * 避免后端把未出现的目录项误判成“应删除”或“应丢失”的历史配置。
 *
 * @param catalog 当前完整模型目录。
 * @param targetKey 本次被切换的模型目录 key。
 * @return 可直接提交给保存接口的完整可见性配置。
 */
function buildNextVisibilityConfig(
  catalog: CodexModelCatalogItem[],
  targetKey: string
): CodexModelVisibilityConfig {
  return catalog.reduce<CodexModelVisibilityConfig>((result, item) => {
    result[item.key] = {
      visible: item.key === targetKey ? !item.visible : item.visible,
    };
    return result;
  }, {});
}

/**
 * Codex 模型展示设置卡片。
 * 该组件只消费后端聚合后的统一模型目录，并在前端完成：
 * 1. 搜索过滤；
 * 2. 默认折叠态与完整目录态切换；
 * 3. 模型可见性开关；
 * 4. 不可运行模型的授权提示。
 *
 * @param catalog 后端返回的统一模型目录。
 * @param loading 当前目录是否正在加载。
 * @param onRefresh 重新拉取模型目录。
 * @param onSaveVisibility 保存完整可见性映射。
 * @return Codex 模型展示设置区域。
 */
export default function CodexModelVisibilitySection({
  catalog,
  loading,
  onRefresh,
  onSaveVisibility,
  showHeader = true,
}: CodexModelVisibilitySectionProps) {
  const { t } = useTranslation();
  const [keyword, setKeyword] = useState('');
  const [showAllModels, setShowAllModels] = useState(false);

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
  const previewCatalog = useMemo(
    () => buildPreviewCatalog(filteredCatalog),
    [filteredCatalog]
  );
  const displayedCatalog = useMemo(() => {
    if (isSearching || showAllModels) {
      return filteredCatalog;
    }
    return previewCatalog;
  }, [filteredCatalog, isSearching, previewCatalog, showAllModels]);
  const canExpandAllModels = !isSearching && filteredCatalog.length > previewCatalog.length;

  /**
   * 切换模型可见性时始终按完整目录生成全量配置。
   * 这样 UI 层无论处于折叠态、搜索态还是展开态，都不会破坏保存协议。
   *
   * @param targetKey 被切换的目录项 key。
   */
  const handleToggleVisibility = (targetKey: string) => {
    onSaveVisibility(buildNextVisibilityConfig(catalog, targetKey));
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
          <div className={styles.sectionHeader}>
            <span className={styles.sectionLabel}>
              {isSearching || showAllModels
                ? t('settings.codexProvider.modelsAllSectionTitle')
                : t('settings.codexProvider.modelsVisibleSectionTitle')}
            </span>
          </div>

          <div className={styles.modelList}>
            {displayedCatalog.map((item) => (
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
              </article>
            ))}
          </div>

          {canExpandAllModels && (
            <div className={styles.footer}>
              <button
                type="button"
                className={styles.secondaryButton}
                onClick={() => setShowAllModels((current) => !current)}
              >
                {showAllModels
                  ? t('settings.codexProvider.modelsCollapse')
                  : t('settings.codexProvider.modelsViewAll')}
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
