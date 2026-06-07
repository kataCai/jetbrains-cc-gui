import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CodexModelCatalogItem,
  CodexModelVisibilityConfig,
} from '../../../types/provider';

interface CodexModelVisibilitySectionProps {
  catalog: CodexModelCatalogItem[];
  loading: boolean;
  onRefresh: () => void;
  onSaveVisibility: (visibilityConfig: CodexModelVisibilityConfig) => void;
}

/**
 * Codex 模型展示开关面板。
 * 该组件只消费后端聚合后的统一模型目录，并在前端完成搜索、可见性切换和不可运行态提示。
 * 这里不自行推导 provider/models 关系，避免再次回到“聊天区和设置页各自拼目录”的旧实现。
 *
 * @param catalog 后端返回的统一模型目录
 * @param loading 目录是否正在加载
 * @param onRefresh 重新拉取模型目录
 * @param onSaveVisibility 保存完整可见性映射
 * @return Codex 模型展示开关区域
 */
export default function CodexModelVisibilitySection({
  catalog,
  loading,
  onRefresh,
  onSaveVisibility,
}: CodexModelVisibilitySectionProps) {
  const { t } = useTranslation();
  const [keyword, setKeyword] = useState('');

  /**
   * 目录展示层的搜索只做纯前端过滤，不改写原始 catalog。
   * 这样切换搜索词不会污染可见性状态，也便于后续扩展来源筛选。
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

  /**
   * 后端保存接口约定接收完整 visibility 映射。
   * 因此前端切换任意一个模型时，都基于当前 catalog 重新生成全量配置，避免只传局部增量导致历史项丢失。
   *
   * @param targetKey 被切换的目录项 key
   */
  const handleToggleVisibility = (targetKey: string) => {
    const nextVisibility = catalog.reduce<CodexModelVisibilityConfig>((result, item) => {
      result[item.key] = {
        visible: item.key === targetKey ? !item.visible : item.visible,
      };
      return result;
    }, {});
    onSaveVisibility(nextVisibility);
  };

  return (
    <section>
      <div>
        <h4>{t('settings.codexProvider.modelsTitle')}</h4>
        <p>{t('settings.codexProvider.modelsDescription')}</p>
      </div>

      <div>
        <input
          type="text"
          value={keyword}
          placeholder={t('settings.codexProvider.modelsSearchPlaceholder')}
          onChange={(event) => setKeyword(event.target.value)}
        />
        <button type="button" onClick={onRefresh}>
          {t('common.refresh')}
        </button>
      </div>

      {loading ? (
        <div>{t('common.loading')}</div>
      ) : filteredCatalog.length === 0 ? (
        <div>{t('settings.codexProvider.modelsEmpty')}</div>
      ) : (
        <div>
          {filteredCatalog.map((item) => (
            <div key={item.key}>
              <div>
                <div>{item.label}</div>
                <div>{item.providerName}</div>
                {item.description && <div>{item.description}</div>}
                <div>{t(`settings.codexProvider.modelsSource.${item.source}`)}</div>
                {!item.runnable && (
                  <div>{t('settings.codexProvider.modelsUnavailable')}</div>
                )}
              </div>
              <button
                type="button"
                aria-label={`toggle:${item.key}`}
                aria-pressed={item.visible}
                onClick={() => handleToggleVisibility(item.key)}
              >
                {item.visible ? 'ON' : 'OFF'}
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
