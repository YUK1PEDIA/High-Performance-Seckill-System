package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.apache.logging.log4j.message.ReusableMessage;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据缓存实现 id 查询商铺
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 防止缓存穿透的查询方式
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
//        Shop shop = cacheClient
//                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 使用逻辑过期解决缓存击穿
//    public Shop queryWithLogicalExpire(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 根据 id 查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isBlank(shopJson)) {
//            // 没有命中就返回（命中了还需要查询是否逻辑过期）
//            return null;
//        }
//
//        // 命中，需要判断过期时间
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        // 此处不能直接把 Object 转成 Shop，需要先转为 JSONObject
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        // 判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            // 没有过期
//            return shop;
//        }
//
//        // 已经过期，进行缓存重建
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        // 判断获取锁是否成功
//        if (isLock) {
//            // 获取锁成功，开启独立线程实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    this.saveShop2Redis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unLock(lockKey);
//                }
//            });
//        }
//
//        return shop;
//    }

    // 用互斥锁解决缓存击穿
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 在 redis 里查询店铺信息
        Shop shop = queryRedisWithKey(key);
        if (shop != null) { // redis 命中
            return shop;
        }

        // redis 未命中，查询后端数据库
        String lockKey = "lock:shop:" + id;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取锁失败，当前线程需要休眠后重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 获取锁成功，再次查询一次 redis 缓存
            shop = queryRedisWithKey(key);
            if (shop != null) {
                return shop;
            }

            // 如果第二次查询仍然没有命中，就查询后端数据库
            shop = getById(id);
            if (shop == null) {
                // 往 redis 里写入空值，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 后端数据库存在对应店铺，就写入 redis 缓存中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unLock(lockKey);
        }

        return shop;
    }

    // 根据 id 在 redis 缓存中查询店铺
    private Shop queryRedisWithKey(String key) {
        // 根据 id 查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // redis 中存在，直接返回（isNotBlank只有在字符串为"abc"这种时才返回 true，空字符串、空格回车都返回 false）
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否为空值
        if (shopJson != null) {
            return null;
        }
        // redis 里没有查询到，返回空
        return null;
    }

    // 防止缓存穿透
//    public Shop queryWithPassThrough(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 根据 id 查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            // redis 中存在，直接返回（isNotBlank只有在字符串为"abc"这种时才返回 true，空字符串、空格回车都返回 false）
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 判断命中的是否为空值
//        if (shopJson != null) {
//            return null;
//        }
//
//        // redis 中不存在，查询后端数据库
//        Shop shop = getById(id);
//        if (shop == null) {
//            // 往 redis 里写入空值，防止缓存穿透
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        // 后端数据库存在对应店铺，就写入 redis 缓存中
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

      // 将数据库数据写入 redis 中，并写入逻辑过期时间
//    public void saveShop2Redis(Long id, Long expireSeconds) {
//        // 查询店铺数据
//        Shop shop = getById(id);
//        // 封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        // 写入 redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
//    }

    /**
     * 查询店铺更新缓存
     * 需要先更新数据库，再删除缓存来实现缓存的更新，这样发生线程安全的问题更小
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 根据类型查找店铺
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 是否需要根据坐标查询
        if (x == null || y == null) {
            // 直接按数据库查询
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 查询 redis，按照距离排序分页，结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        // 查询 5km 以内的商家
                        new Distance(5000),
                        // 此处默认从 0 查到 end，并不能完成截取
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 如果 list 大小小于 from，那么就不能截取
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        // 截取从 from 到 end 的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 获取店铺 id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 根据 id 查询 shop (redis 中只存了店铺 id，具体信息还需要到数据库中查)
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(ID," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
