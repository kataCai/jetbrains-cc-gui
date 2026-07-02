import styles from './style.module.less';
import { useTranslation } from 'react-i18next';

export interface EnvironmentTabProps {
  nodePath: string;
  onNodePathChange: (path: string) => void;
  onSaveNodePath: () => void;
  savingNodePath: boolean;
  nodeVersion?: string | null;
  minNodeVersion?: number;
  claudeCliPath?: string;
  onClaudeCliPathChange?: (path: string) => void;
  onSaveClaudeCliPath?: () => void;
  savingClaudeCliPath?: boolean;
  workingDirectory?: string;
  onWorkingDirectoryChange?: (dir: string) => void;
  onSaveWorkingDirectory?: () => void;
  savingWorkingDirectory?: boolean;
  codexHistoryImageCacheDir?: string;
  codexHistoryImageCacheResolvedDir?: string;
  codexHistoryImageCacheRetentionDays?: number;
  codexHistoryImageCacheMaxSizeMb?: number;
  onCodexHistoryImageCacheDirChange?: (dir: string) => void;
  onCodexHistoryImageCacheRetentionDaysChange?: (days: number) => void;
  onCodexHistoryImageCacheMaxSizeMbChange?: (size: number) => void;
  onBrowseCodexHistoryImageCacheDir?: () => void;
  onResetCodexHistoryImageCacheDir?: () => void;
  onSaveCodexHistoryImageCacheConfig?: () => void;
  savingCodexHistoryImageCache?: boolean;
}

const EnvironmentTab = ({
  nodePath,
  onNodePathChange,
  onSaveNodePath,
  savingNodePath,
  nodeVersion,
  minNodeVersion = 18,
  claudeCliPath = '',
  onClaudeCliPathChange = () => {},
  onSaveClaudeCliPath = () => {},
  savingClaudeCliPath = false,
  workingDirectory = '',
  onWorkingDirectoryChange = () => {},
  onSaveWorkingDirectory = () => {},
  savingWorkingDirectory = false,
  codexHistoryImageCacheDir = '',
  codexHistoryImageCacheResolvedDir = '',
  codexHistoryImageCacheRetentionDays = 30,
  codexHistoryImageCacheMaxSizeMb = 1024,
  onCodexHistoryImageCacheDirChange = () => {},
  onCodexHistoryImageCacheRetentionDaysChange = () => {},
  onCodexHistoryImageCacheMaxSizeMbChange = () => {},
  onBrowseCodexHistoryImageCacheDir = () => {},
  onResetCodexHistoryImageCacheDir = () => {},
  onSaveCodexHistoryImageCacheConfig = () => {},
  savingCodexHistoryImageCache = false,
}: EnvironmentTabProps) => {
  const { t } = useTranslation();

  // Parse the major version number
  const parseMajorVersion = (version: string | null | undefined): number => {
    if (!version) return 0;
    const versionStr = version.startsWith('v') ? version.substring(1) : version;
    const dotIndex = versionStr.indexOf('.');
    if (dotIndex > 0) {
      return parseInt(versionStr.substring(0, dotIndex), 10) || 0;
    }
    return parseInt(versionStr, 10) || 0;
  };

  const majorVersion = parseMajorVersion(nodeVersion);
  const isVersionTooLow = nodeVersion && majorVersion > 0 && majorVersion < minNodeVersion;

  return (
    <div className={styles.tabContent}>
      {/* Node.js path configuration */}
      <div className={styles.nodePathSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-terminal" />
          <span className={styles.fieldLabel}>{t('settings.basic.nodePath.label')}</span>
          {nodeVersion && (
            <span className={`${styles.versionBadge} ${isVersionTooLow ? styles.versionBadgeError : styles.versionBadgeOk}`}>
              {nodeVersion}
            </span>
          )}
        </div>
        {isVersionTooLow && (
          <div className={styles.versionWarning}>
            <span className="codicon codicon-warning" />
            {t('settings.basic.nodePath.versionTooLow', { minVersion: minNodeVersion })}
          </div>
        )}
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.nodePath.placeholder')}
            value={nodePath}
            onChange={(e) => onNodePathChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveNodePath}
            disabled={savingNodePath}
          >
            {savingNodePath && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>
            {t('settings.basic.nodePath.hint')} <code>{t('settings.basic.nodePath.hintCommand')}</code> {t('settings.basic.nodePath.hintText')}
          </span>
        </small>
      </div>

      {/* Claude CLI path configuration */}
      <div className={styles.nodePathSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-rocket" />
          <span className={styles.fieldLabel}>{t('settings.basic.claudeCliPath.label')}</span>
        </div>
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.claudeCliPath.placeholder')}
            value={claudeCliPath}
            onChange={(e) => onClaudeCliPathChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveClaudeCliPath}
            disabled={savingClaudeCliPath}
          >
            {savingClaudeCliPath && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.claudeCliPath.hint')}</span>
        </small>
      </div>

      {/* Working directory configuration */}
      <div className={styles.workingDirSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-folder" />
          <span className={styles.fieldLabel}>{t('settings.basic.workingDirectory.label')}</span>
        </div>
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.workingDirectory.placeholder')}
            value={workingDirectory}
            onChange={(e) => onWorkingDirectoryChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveWorkingDirectory}
            disabled={savingWorkingDirectory}
          >
            {savingWorkingDirectory && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>
            {t('settings.basic.workingDirectory.hint')}
          </span>
        </small>
      </div>

      <div className={styles.workingDirSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-device-camera" />
          <span className={styles.fieldLabel}>{t('settings.basic.codexHistoryImageCache.label')}</span>
        </div>
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.codexHistoryImageCache.dirPlaceholder')}
            value={codexHistoryImageCacheDir}
            onChange={(e) => onCodexHistoryImageCacheDirChange(e.target.value)}
          />
          <div className={styles.inlineActionGroup}>
            <button
              className={styles.saveBtn}
              onClick={onBrowseCodexHistoryImageCacheDir}
              type="button"
            >
              {t('common.browse')}
            </button>
            <button
              className={styles.saveBtn}
              onClick={onResetCodexHistoryImageCacheDir}
              type="button"
            >
              {t('common.reset')}
            </button>
          </div>
        </div>
        <div className={styles.inlineNumberGrid}>
          <label className={styles.numberField}>
            <span className={styles.numberFieldLabel}>
              {t('settings.basic.codexHistoryImageCache.retentionDays')}
            </span>
            <input
              type="number"
              min={1}
              max={365}
              className={styles.nodePathInput}
              value={codexHistoryImageCacheRetentionDays}
              onChange={(e) => onCodexHistoryImageCacheRetentionDaysChange(Number(e.target.value) || 1)}
            />
          </label>
          <label className={styles.numberField}>
            <span className={styles.numberFieldLabel}>
              {t('settings.basic.codexHistoryImageCache.maxSizeMb')}
            </span>
            <input
              type="number"
              min={64}
              max={10240}
              className={styles.nodePathInput}
              value={codexHistoryImageCacheMaxSizeMb}
              onChange={(e) => onCodexHistoryImageCacheMaxSizeMbChange(Number(e.target.value) || 64)}
            />
          </label>
          <button
            className={styles.saveBtn}
            onClick={onSaveCodexHistoryImageCacheConfig}
            disabled={savingCodexHistoryImageCache}
            type="button"
          >
            {savingCodexHistoryImageCache && (
              <span className="codicon codicon-loading codicon-modifier-spin" />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>
            {t('settings.basic.codexHistoryImageCache.hint')}
            {' '}
            <code>{codexHistoryImageCacheResolvedDir}</code>
          </span>
        </small>
      </div>
    </div>
  );
};

export default EnvironmentTab;
