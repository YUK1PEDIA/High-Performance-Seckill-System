package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 通过静态代码块加载 lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 新建线程池处理阻塞队列里的订单信息
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /*private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 获取失败，说明没有消息，执行下一次循环
                        continue;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 获取成功，可以下单创建订单
                    handleVoucherOrder(voucherOrder);
                    // ACK 确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 获取 pending-list 中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 获取失败，说明 pending-list 没有消息，结束循环
                        break;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 获取成功，可以下单创建订单
                    handleVoucherOrder(voucherOrder);
                    // ACK 确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理 pending-list 异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }*/

    // 阻塞队列的特点：一个线程尝试从阻塞队列中拿元素时，如果阻塞队列中没有元素，则该线程会被阻塞
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 线程任务，处理阻塞队列里的订单信息，并且需要在当前类初始化后赶紧执行，于是需要上面的 init 方法
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }


    // 拿到阻塞队列里的订单信息后创建订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户 id
        Long userId = voucherOrder.getUserId();
        // Rlock 是 redisson 框架提供的分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // spring 的事务实现是通过动态代理技术实现的，拿到代理对象来调用方法能保证事务的有效性
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    // 代理对象
    private IVoucherOrderService proxy;
    /**
     * 优惠券秒杀下单
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 先获取用户 id
        Long userId = UserHolder.getUser().getId();
        // 获取订单 id
        long orderId = redisIdWorker.nextId("order");
        // 执行 lua 脚本，判断购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 从主线程中获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单 id
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 先获取用户 id
        Long userId = UserHolder.getUser().getId();
        // 执行 lua 脚本，判断购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // lua 脚本返回结果为 0，说明有购买资格，根据下单信息创建订单并保存到阻塞队列里
        VoucherOrder voucherOrder = new VoucherOrder();
        // 全局唯一的订单 id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 将订单信息存到阻塞队列中
        orderTasks.add(voucherOrder);

        // 从主线程中获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单 id
        return Result.ok(orderId);
    }*/

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("不在秒杀的时间范围内！");
//        }
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        // 分布式锁实现
//        // SimpleRedisLock 是自己实现的分布式锁
////        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//
//        // Rlock 是 redisson 框架提供的分布式锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("不允许重复下单！");
//        }
//
//        try {
//            // 获取 spring 事务的代理对象，如果直接调用 createVoucherOrder 方法可能会造成 spring 事务失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            // spring 的事务实现是通过动态代理技术实现的，拿到代理对象来调用方法能保证事务的有效性
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 通过阻塞队列拿到的订单信息获取 userId
        Long userId = voucherOrder.getUserId();
        // 优惠券一个用户最多购买一次
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                // 第二个 gt 是加了一个乐观锁，判断现在查询到的库存和之前的库存是否一样，从而来判断数据库是否被修改过
                // 能解决高并发下的线程安全问题
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            // 扣减库存失败
            return;
        }

        // 创建订单
        save(voucherOrder);
    }

/*    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 通过拦截器获取登录用户名
        Long userId = UserHolder.getUser().getId();
        // 优惠券一个用户最多购买一次
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                // 第二个 gt 是加了一个乐观锁，判断现在查询到的库存和之前的库存是否一样，从而来判断数据库是否被修改过
                // 能解决高并发下的线程安全问题
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减库存失败
            return Result.fail("库存不足！");
        }

        // 接下来创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 生成全局唯一订单 id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        // 设置优惠券 id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 返回订单 id
        return Result.ok(orderId);
    }*/
}
