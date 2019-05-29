package top.alertcode.adelina.framework.utils;


import top.alertcode.adelina.framework.commons.model.TreeNode;

import java.util.List;
import java.util.Objects;


/**
 * <p>
 * Tree工具类
 * </p>
 *
 * @author Caratacus
 */
public abstract class TreeUtils {

    /**
     * 递归查找子节点
     *
     * @param treeNodes 子节点
     * @return T extends TreeNode
     */
    public static <T extends TreeNode> T findChildren(T treeNode, List<T> treeNodes) {
        treeNodes.stream().filter(e -> Objects.equals(treeNode.getId(), e.getParentId())).forEach(e -> treeNode.getChildrens().add(findChildren(e, treeNodes)));
        return treeNode;
    }
}
