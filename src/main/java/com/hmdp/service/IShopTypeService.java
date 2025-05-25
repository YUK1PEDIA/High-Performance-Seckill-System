package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IShopTypeService extends IService<ShopType> {

    /**
     * 缓存查询商铺类型
     * @return
     */
    Result cacheQuery();
}
