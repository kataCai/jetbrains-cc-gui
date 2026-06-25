import { useEffect } from 'react';
import { sendBridgeEvent } from '../utils/bridge';
import { insertNewlineAtCursor } from './useContextMenu';
import { copyActiveImageTarget } from '../utils/activeImageTarget';

/**
 * Registers IDEA shortcut action handler (copy/cut/send/newline from Java-registered Actions).
 */
export function useContextActions() {
  useEffect(() => {
    window.execContextAction = (action: string) => {
      switch (action) {
        case 'copy': {
          // 优先复制当前激活图片，补齐 IDE Ctrl/Cmd+C 在非文本选区下的图片复制能力。
          void copyActiveImageTarget().then((copied) => {
            if (copied) {
              return;
            }

            const activeEl = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
            let text = '';
            if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
              text = activeEl.value.substring(activeEl.selectionStart ?? 0, activeEl.selectionEnd ?? 0);
            } else {
              text = window.getSelection()?.toString() ?? '';
            }
            if (text) {
              sendBridgeEvent('write_clipboard', text);
            }
          });
          break;
        }
        case 'cut': {
          const activeEl = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
          let text = '';
          if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            const start = activeEl.selectionStart ?? 0;
            const end = activeEl.selectionEnd ?? 0;
            text = activeEl.value.substring(start, end);
            if (text) {
              activeEl.setRangeText('', start, end, 'end');
              activeEl.dispatchEvent(new Event('input', { bubbles: true }));
            }
          } else {
            text = window.getSelection()?.toString() ?? '';
            if (text) {
              document.execCommand('delete');
            }
          }
          if (text) {
            sendBridgeEvent('write_clipboard', text);
          }
          break;
        }
        case 'send': {
          document.dispatchEvent(new CustomEvent('ideaSend'));
          break;
        }
        case 'newline': {
          const activeEl = document.activeElement;
          if (activeEl && activeEl.getAttribute('contenteditable') === 'true') {
            insertNewlineAtCursor();
          }
          break;
        }
      }
    };

    return () => {
      delete window.execContextAction;
    };
  }, []);
}
