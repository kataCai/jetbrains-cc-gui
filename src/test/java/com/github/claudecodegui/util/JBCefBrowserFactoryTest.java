package com.github.claudecodegui.util;

import org.cef.handler.CefKeyboardHandler;
import org.junit.Assert;
import org.junit.Test;

public class JBCefBrowserFactoryTest {

    @Test
    public void suppressesControlCharOnNonEditableField() {
        CefKeyboardHandler.CefKeyEvent event = new CefKeyboardHandler.CefKeyEvent(
                CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_CHAR,
                4,
                19,
                31,
                false,
                (char) 0x13,
                (char) 0x13,
                false
        );

        Assert.assertTrue(JBCefBrowserFactory.shouldSuppressProblematicCharEvent(event));
    }

    @Test
    public void keepsControlCharForEditableField() {
        CefKeyboardHandler.CefKeyEvent event = new CefKeyboardHandler.CefKeyEvent(
                CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_CHAR,
                4,
                19,
                31,
                false,
                (char) 0x13,
                (char) 0x13,
                true
        );

        Assert.assertFalse(JBCefBrowserFactory.shouldSuppressProblematicCharEvent(event));
    }

    @Test
    public void keepsNonCharEvents() {
        CefKeyboardHandler.CefKeyEvent event = new CefKeyboardHandler.CefKeyEvent(
                CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_KEYDOWN,
                4,
                19,
                31,
                false,
                (char) 0x13,
                (char) 0x13,
                false
        );

        Assert.assertFalse(JBCefBrowserFactory.shouldSuppressProblematicCharEvent(event));
    }

    @Test
    public void suppressesZeroCharOnNonEditableFieldWhenWindowsKeyCodeIsPresent() {
        CefKeyboardHandler.CefKeyEvent event = new CefKeyboardHandler.CefKeyEvent(
                CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_CHAR,
                4,
                4,
                32,
                false,
                (char) 0,
                (char) 0,
                false
        );

        Assert.assertTrue(JBCefBrowserFactory.shouldSuppressProblematicCharEvent(event));
    }

    @Test
    public void keepsPrintableChars() {
        CefKeyboardHandler.CefKeyEvent event = new CefKeyboardHandler.CefKeyEvent(
                CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_CHAR,
                0,
                65,
                65,
                false,
                'a',
                'a',
                false
        );

        Assert.assertFalse(JBCefBrowserFactory.shouldSuppressProblematicCharEvent(event));
    }

    @Test
    public void enablesCustomContextMenuWhenRightClickDevtoolsToggleIsEnabled() {
        // 生产环境默认必须禁用原生右键菜单，避免把整套浏览器菜单暴露给普通用户。
        Assert.assertTrue(JBCefBrowserFactory.shouldDisableContextMenu(false, false));
        // 显式开启后，无论是否开发模式，都应该保留右键菜单以展示 DevTools 入口。
        Assert.assertFalse(JBCefBrowserFactory.shouldDisableContextMenu(true, false));
        Assert.assertFalse(JBCefBrowserFactory.shouldDisableContextMenu(true, true));
        // 开发模式下即使未显式开启，也沿用现有调试习惯保留右键菜单。
        Assert.assertFalse(JBCefBrowserFactory.shouldDisableContextMenu(false, true));
    }
}
