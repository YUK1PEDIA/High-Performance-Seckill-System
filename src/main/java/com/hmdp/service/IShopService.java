package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

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

    /**
     * 根据类型查找店铺
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
