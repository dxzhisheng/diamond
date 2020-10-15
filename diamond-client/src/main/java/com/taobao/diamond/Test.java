package com.taobao.diamond;

import com.taobao.diamond.manager.DiamondManager;
import com.taobao.diamond.manager.ManagerListener;
import com.taobao.diamond.manager.impl.DefaultDiamondManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.concurrent.Executor;

/**
 * @author dx
 * @date 2020-10-15 15:43
 * <p>
 * https://www.cnblogs.com/zhangyaxiao/p/8183617.html
 */
public class Test {
    private static final Log log = LogFactory.getLog(Test.class);
    public static void main(String[] args) {
        DiamondManager manager = new DefaultDiamondManager("DEFAULT_GROUP", "dxkey", new ManagerListener() {
            public Executor getExecutor() {
                return null;
            }

            public void receiveConfigInfo(String configInfo) {
                // 客户端处理数据的逻辑
                System.out.println("========="+configInfo);
            }
        });
    }

}
