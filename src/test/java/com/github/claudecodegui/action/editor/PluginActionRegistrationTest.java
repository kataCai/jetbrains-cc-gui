package com.github.claudecodegui.action.editor;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * 校验插件动作菜单注册是否完整。
 * 该测试只关注 XML 层面的动作挂载点，避免后续调整菜单时遗漏项目树或编辑器标签页中的入口。
 */
public class PluginActionRegistrationTest {

    /**
     * 校验发送文件路径动作同时出现在项目树和编辑器标签页菜单中。
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
     * 读取指定动作在 plugin.xml 中注册到的菜单组列表。
     *
     * @param actionId 插件动作 ID
     * @return 动作声明的 group-id 集合
     * @throws Exception 当 XML 解析失败或动作不存在时抛出异常
     */
    private static Set<String> getActionGroupIds(String actionId) throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File("src/main/resources/META-INF/plugin.xml"));
        NodeList actions = document.getElementsByTagName("action");
        for (int i = 0; i < actions.getLength(); i++) {
            Element action = (Element) actions.item(i);
            if (actionId.equals(action.getAttribute("id"))) {
                Set<String> groupIds = new HashSet<>();
                NodeList addToGroups = action.getElementsByTagName("add-to-group");
                for (int j = 0; j < addToGroups.getLength(); j++) {
                    Element addToGroup = (Element) addToGroups.item(j);
                    groupIds.add(addToGroup.getAttribute("group-id"));
                }
                return groupIds;
            }
        }
        throw new AssertionError("Action not found: " + actionId);
    }
}
