package top.alertcode.adelina.framework.lock;

import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import top.alertcode.adelina.framework.BaseTest;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁test
 *
 * @author fuqiang
 * @date 2019-06-05
 * @copyright fero.com.cn
 */
public class DisLockTest extends BaseTest {

    @Autowired
    private Redisson redisson;

    @Test
    public void getLock() {
        RLock lock = redisson.getLock("lockName");
        try {
            // 1. 最常见的使用方法
            //lock.lock();
            // 2. 支持过期解锁功能,10秒钟以后自动解锁, 无需调用unlock方法手动解锁
            //lock.lock(10, TimeUnit.SECONDS);
            // 3. 尝试加锁，最多等待2秒，上锁以后8秒自动解锁
            boolean res = lock.tryLock(2, 8, TimeUnit.SECONDS);
            if (res) { //成功
                //处理业务
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //释放锁
            lock.unlock();
        }
    }
}