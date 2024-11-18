package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 通过缓存实现 id 查询商铺
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 查询店铺更新缓存
     * @param shop
     * @return
     */
    Result update(Shop shop);
}
