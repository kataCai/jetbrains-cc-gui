package com.github.claudecodegui.action.editor;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验 plugin.xml 中关键编辑器动作的菜单注册关系。
 * 这里同时覆盖当前主线新增的文件路径发送入口，以及上游补充的编辑器菜单动作顺序和图标约束。
 */
public class PluginActionRegistrationTest {

    /**
     * 校验发送文件路径动作同时挂载到项目树和编辑器标签页菜单。
     *
     * @throws Exception 当 plugin.xml 解析失败或动作缺失时抛出异常
     */
    @Test
    public void sendFilePathActionAppearsInProjectTreeAndEditorTabMenus() throws Exception {
        Set<String> groupIds = getActionGroupIds("ClaudeCodeGUI.SendFilePathToInputAction");

        Assert.assertTrue(groupIds.contains("ProjectViewPopupMenu"));
        Assert.assertTrue(groupIds.contains("EditorTabPopupMenu"));
    }

    /**
     * 校验复制引用动作挂载到编辑器右键菜单，并位于发送选区动作之后。
     *
     * @throws Exception 当 plugin.xml 解析失败或动作缺失时抛出异常
     */
    @Test
    public void copySelectionReferenceActionAppearsInEditorPopupMenuAfterSendSelectionAction() throws Exception {
        ActionRegistration action = getActionRegistration("ClaudeCodeGUI.CopySelectionReferenceAction");
        ActionRegistration quickFixAction = getActionRegistration("ClaudeCodeGUI.QuickFixWithClaudeAction");

        Assert.assertEquals(
                "com.github.claudecodegui.action.editor.CopySelectionReferenceAction",
                action.actionClass
        );
        AddToGroupRegistration editorPopup = action.getAddToGroup("EditorPopupMenu");
        Assert.assertEquals("after", editorPopup.anchor);
        Assert.assertEquals("ClaudeCodeGUI.SendSelectionToTerminalAction", editorPopup.relativeToAction);
        Assert.assertTrue(action.declarationIndex < quickFixAction.declarationIndex);
    }

    /**
     * 校验编辑器右键相关动作统一使用插件图标，避免并轨后图标退化。
     *
     * @throws Exception 当 plugin.xml 解析失败或动作缺失时抛出异常
     */
    @Test
    public void editorPopupActionsUseExpectedIcons() throws Exception {
        ActionRegistration sendSelectionAction = getActionRegistration("ClaudeCodeGUI.SendSelectionToTerminalAction");
        ActionRegistration copyReferenceAction = getActionRegistration("ClaudeCodeGUI.CopySelectionReferenceAction");
        ActionRegistration quickFixAction = getActionRegistration("ClaudeCodeGUI.QuickFixWithClaudeAction");

        Assert.assertEquals("/icons/cc-gui-icon.svg", sendSelectionAction.icon);
        Assert.assertEquals("/icons/cc-gui-icon.svg", copyReferenceAction.icon);
        Assert.assertEquals("/icons/cc-gui-icon.svg", quickFixAction.icon);
    }

    /**
     * 读取指定动作挂载到的 group-id 集合。
     *
     * @param actionId 插件动作 ID
     * @return 该动作声明的菜单组集合
     * @throws Exception 当 plugin.xml 解析失败或动作不存在时抛出异常
     */
    private static Set<String> getActionGroupIds(String actionId) throws Exception {
        return getActionRegistration(actionId).getGroupIds();
    }

    /**
     * 读取指定动作在 plugin.xml 中的完整注册信息。
     *
     * @param actionId 插件动作 ID
     * @return 动作注册信息
     * @throws Exception 当 plugin.xml 解析失败或动作不存在时抛出异常
     */
    private static ActionRegistration getActionRegistration(String actionId) throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File("src/main/resources/META-INF/plugin.xml"));
        NodeList actions = document.getElementsByTagName("action");
        for (int i = 0; i < actions.getLength(); i++) {
            Element action = (Element) actions.item(i);
            if (actionId.equals(action.getAttribute("id"))) {
                List<AddToGroupRegistration> addToGroupRegistrations = new ArrayList<>();
                NodeList addToGroups = action.getElementsByTagName("add-to-group");
                for (int j = 0; j < addToGroups.getLength(); j++) {
                    Element addToGroup = (Element) addToGroups.item(j);
                    addToGroupRegistrations.add(new AddToGroupRegistration(
                            addToGroup.getAttribute("group-id"),
                            addToGroup.getAttribute("anchor"),
                            addToGroup.getAttribute("relative-to-action")
                    ));
                }
                return new ActionRegistration(
                        action.getAttribute("class"),
                        action.getAttribute("icon"),
                        addToGroupRegistrations,
                        i
                );
            }
        }
        throw new AssertionError("Action not found: " + actionId);
    }

    /**
     * XML 中单个 action 的抽象表示。
     */
    private static final class ActionRegistration {
        private final String actionClass;
        private final String icon;
        private final List<AddToGroupRegistration> addToGroupRegistrations;
        private final int declarationIndex;

        private ActionRegistration(
                String actionClass,
                String icon,
                List<AddToGroupRegistration> addToGroupRegistrations,
                int declarationIndex
        ) {
            this.actionClass = actionClass;
            this.icon = icon;
            this.addToGroupRegistrations = addToGroupRegistrations;
            this.declarationIndex = declarationIndex;
        }

        /**
         * 提取该动作声明的菜单组 ID 集合。
         *
         * @return group-id 集合
         */
        private Set<String> getGroupIds() {
            Set<String> groupIds = new HashSet<>();
            for (AddToGroupRegistration registration : addToGroupRegistrations) {
                groupIds.add(registration.groupId);
            }
            return groupIds;
        }

        /**
         * 读取指定 group-id 对应的挂载信息。
         *
         * @param groupId 菜单组 ID
         * @return 对应挂载信息
         */
        private AddToGroupRegistration getAddToGroup(String groupId) {
            for (AddToGroupRegistration registration : addToGroupRegistrations) {
                if (groupId.equals(registration.groupId)) {
                    return registration;
                }
            }
            throw new AssertionError("Group registration not found: " + groupId);
        }
    }

    /**
     * XML 中单个 add-to-group 的抽象表示。
     */
    private static final class AddToGroupRegistration {
        private final String groupId;
        private final String anchor;
        private final String relativeToAction;

        private AddToGroupRegistration(String groupId, String anchor, String relativeToAction) {
            this.groupId = groupId;
            this.anchor = anchor;
            this.relativeToAction = relativeToAction;
        }
    }
}
